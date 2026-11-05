package coredevices.ring.external.geminilive

import com.russhwolf.settings.MapSettings
import coredevices.ring.database.PreferencesImpl
import coredevices.ring.external.replyaudio.ReplyAudioLog
import coredevices.ring.external.replyaudio.ReplyLogEntry
import coredevices.ring.external.replyaudio.ReplyPlaybackLock
import coredevices.ring.external.replyaudio.ReplyStreamPlayer
import coredevices.ring.external.replyaudio.pcm16MonoToWav
import coredevices.ring.service.RecordingBackgroundScope
import coredevices.ring.service.button.GestureDestination
import coredevices.ring.service.button.GestureRoutingPreferences
import coredevices.ring.service.button.RingGesture
import coredevices.util.integrations.IntegrationTokenStorage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

private fun content(
    audio: ByteArray? = null,
    input: String? = null,
    output: String? = null,
    turnComplete: Boolean = false,
): String {
    val parts = audio?.let {
        """"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"${Base64.encode(it)}"}}]},"""
    }.orEmpty()
    val inputPart = input?.let { """"inputTranscription":{"text":"$it"},""" }.orEmpty()
    val outputPart = output?.let { """"outputTranscription":{"text":"$it"},""" }.orEmpty()
    return """{"serverContent":{$parts$inputPart$outputPart"turnComplete":$turnComplete}}"""
}

private const val SETUP_COMPLETE = """{"setupComplete":{}}"""

private class FakeConnection(script: List<String>) : GeminiLiveConnection {
    val sent = mutableListOf<String>()
    var closed = false
    override val incoming = Channel<String>(Channel.UNLIMITED).also { ch -> script.forEach { ch.trySend(it) } }

    override suspend fun send(message: String) {
        sent += message
    }

    override suspend fun close() {
        closed = true
        incoming.close()
    }
}

private class FakeConnector(private val scripts: MutableList<List<String>>) : GeminiLiveConnector {
    val connections = mutableListOf<FakeConnection>()
    val keys = mutableListOf<String>()

    override suspend fun connect(apiKey: String): GeminiLiveConnection {
        keys += apiKey
        return FakeConnection(scripts.removeAt(0)).also { connections += it }
    }
}

private class MemoryTokenStorage : IntegrationTokenStorage {
    val tokens = mutableMapOf<String, String>()
    override suspend fun saveToken(key: String, token: String) {
        tokens[key] = token
    }
    override suspend fun getToken(key: String) = tokens[key]
    override suspend fun deleteToken(key: String) {
        tokens.remove(key)
    }
}

class GeminiLiveProtocolTest {

    @Test
    fun setupCarriesModelHistoryAndManualActivity() {
        val setup = Json.parseToJsonElement(
            GeminiLiveProtocol.setup(listOf(GeminiTurn("こんにちは", "どうも")))
        ).jsonObject["setup"]!!.jsonObject

        assertEquals("models/gemini-3.8-live", setup["model"]!!.jsonPrimitive.content)
        assertEquals("AUDIO", setup["generationConfig"]!!.jsonObject["responseModalities"]!!.jsonArray[0].jsonPrimitive.content)
        assertTrue(
            setup["realtimeInputConfig"]!!.jsonObject["automaticActivityDetection"]!!.jsonObject["disabled"]!!.jsonPrimitive.boolean
        )
        val prompt = setup["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(prompt.startsWith(GeminiLiveProtocol.SYSTEM_PROMPT))
        assertTrue("ユーザー: こんにちは\nあなた: どうも" in prompt)
        assertTrue("inputAudioTranscription" in setup)
        assertTrue("outputAudioTranscription" in setup)
    }

    @Test
    fun audioMessageCarriesBase64PcmAndRate() {
        val audio = Json.parseToJsonElement(GeminiLiveProtocol.audio(byteArrayOf(1, 2), 16000))
            .jsonObject["realtimeInput"]!!.jsonObject["audio"]!!.jsonObject
        assertEquals("audio/pcm;rate=16000", audio["mimeType"]!!.jsonPrimitive.content)
        assertContentEquals(byteArrayOf(1, 2), Base64.decode(audio["data"]!!.jsonPrimitive.content))
    }

    @Test
    fun parsesServerMessages() {
        assertEquals(GeminiServerEvent.SetupComplete, GeminiLiveProtocol.parse(SETUP_COMPLETE))
        val event = GeminiLiveProtocol.parse(content(byteArrayOf(5, 6), input = "質問", output = "答え", turnComplete = true))
            as GeminiServerEvent.Content
        assertContentEquals(byteArrayOf(5, 6), event.audio.single())
        assertEquals(24000, event.sampleRate)
        assertEquals("質問", event.inputText)
        assertEquals("答え", event.outputText)
        assertTrue(event.turnComplete)
        assertTrue(GeminiLiveProtocol.parse("""{"error":{"message":"bad key"}}""") is GeminiServerEvent.Error)
        assertNull(GeminiLiveProtocol.parse("""{"usageMetadata":{}}"""))
    }
}

class GeminiLiveSessionTest {

    @Test
    fun sendsTheRecordingBetweenActivityMarkersAndStreamsTheReply() = runTest {
        val connector = FakeConnector(mutableListOf(listOf(
            SETUP_COMPLETE,
            content(input = "今日の"),
            content(byteArrayOf(1, 0), input = "天気は？", output = "晴れ"),
            content(byteArrayOf(2, 0), output = "です。"),
            content(turnComplete = true),
        )))
        val session = GeminiLiveSession.open(connector, "KEY", listOf(GeminiTurn("前", "答")))
        val streamed = mutableListOf<Byte>()
        val pcm = ByteArray(16000 * 2 + 10) { 7 }

        val reply = session.respond(pcm, 16000) { chunk, rate ->
            assertEquals(24000, rate)
            streamed += chunk.toList()
        }

        assertEquals("今日の天気は？", reply.userText)
        assertEquals("晴れです。", reply.replyText)
        assertContentEquals(byteArrayOf(1, 0, 2, 0), reply.audio)
        assertEquals(listOf<Byte>(1, 0, 2, 0), streamed)
        val sent = connector.connections.single().sent.map { Json.parseToJsonElement(it).jsonObject }
        assertTrue("setup" in sent[0])
        assertTrue("activityStart" in sent[1]["realtimeInput"]!!.jsonObject)
        assertTrue("activityEnd" in sent.last()["realtimeInput"]!!.jsonObject)
        val audioParts = sent.drop(2).dropLast(1)
        assertEquals(2, audioParts.size)
        assertEquals(pcm.size, audioParts.sumOf {
            Base64.decode(it["realtimeInput"]!!.jsonObject["audio"]!!.jsonObject["data"]!!.jsonPrimitive.content).size
        })
        assertTrue(connector.connections.single().closed)
    }

    @Test
    fun errorDuringSetupClosesAndThrows() = runTest {
        val connector = FakeConnector(mutableListOf(listOf("""{"error":{"message":"bad key"}}""")))
        assertFailsWith<GeminiLiveException> { GeminiLiveSession.open(connector, "KEY", emptyList()) }
        assertTrue(connector.connections.single().closed)
    }

    @Test
    fun connectionClosedMidReplyThrows() = runTest {
        val connector = FakeConnector(mutableListOf(listOf(SETUP_COMPLETE, content(byteArrayOf(1, 0)))))
        val session = GeminiLiveSession.open(connector, "KEY", emptyList())
        connector.connections.single().incoming.close()
        assertFailsWith<GeminiLiveException> { session.respond(byteArrayOf(0, 0), 16000) { _, _ -> } }
    }
}

class GeminiLiveRouteTest {

    @Test
    fun geminiLiveRouteSurvivesAReload() {
        val stored = MapSettings()
        GestureRoutingPreferences(stored, PreferencesImpl(MapSettings())).setRoute(RingGesture.ClickHold, GestureDestination.GeminiLive)
        assertEquals(
            GestureDestination.GeminiLive,
            GestureRoutingPreferences(stored, PreferencesImpl(MapSettings())).recordingDestinationFor(RingGesture.ClickHold),
        )
    }
}

class GeminiConversationHistoryTest {

    @Test
    fun keepsTheLatestTurnsAcrossReloadsAndClears() = runTest {
        val settings = MapSettings()
        val history = GeminiConversationHistory(settings)
        repeat(GeminiConversationHistory.MAX_TURNS + 2) { history.append(GeminiTurn("q$it", "a$it")) }

        assertEquals(GeminiConversationHistory.MAX_TURNS, history.turns.value.size)
        assertEquals("q2", history.turns.value.first().user)
        assertEquals(history.turns.value, GeminiConversationHistory(settings).turns.value)
        history.clear()
        assertEquals(emptyList(), GeminiConversationHistory(settings).turns.value)
    }
}

class GeminiLiveResponderTest {

    private val now = Instant.fromEpochMilliseconds(1_800_000_000_000)
    private val clock = object : Clock {
        override fun now() = now
    }
    private val settings = MapSettings()
    private val tokens = MemoryTokenStorage()
    private val keyStorage = GeminiLiveKeyStorage(tokens)
    private val history = GeminiConversationHistory(settings)
    private val log = ReplyAudioLog(settings) { Path(SystemTemporaryDirectory, "gemini-live-test-${Uuid.random()}") }
    private val routing = GestureRoutingPreferences(MapSettings(), PreferencesImpl(MapSettings())).also {
        it.setRoute(RingGesture.Hold, GestureDestination.GeminiLive)
    }

    private fun TestScope.manager(connector: FakeConnector) =
        GeminiLiveSessionManager(connector, keyStorage, history, routing, RecordingBackgroundScope(backgroundScope))

    private fun TestScope.responder(
        connector: FakeConnector,
        player: ReplyStreamPlayer = ReplyStreamPlayer { _, chunks -> for (chunk in chunks) Unit },
        sessions: GeminiLiveSessionManager = manager(connector),
    ) = GeminiLiveResponder(
        sessions = sessions,
        keyStorage = keyStorage,
        history = history,
        replyLog = log,
        streamPlayer = player,
        playbackLock = ReplyPlaybackLock(),
        readRecording = { byteArrayOf(9, 9) to 16000 },
        clock = clock,
    )

    private fun replyScript(user: String, answer: String) = listOf(
        SETUP_COMPLETE,
        content(byteArrayOf(1, 0), input = user, output = answer),
        content(byteArrayOf(2, 0)),
        content(turnComplete = true),
    )

    @Test
    fun replyPlaysAsItStreamsAndIsLoggedWithWhatTheUserSaid() = runTest {
        tokens.saveToken("gemini_live_api_key", "KEY")
        val connector = FakeConnector(mutableListOf(replyScript("質問", "答え")))
        val played = mutableListOf<Byte>()
        responder(connector, player = { rate, chunks -> assertEquals(24000, rate); for (c in chunks) played += c.toList() })
            .respond("file-1")

        assertEquals(listOf<Byte>(1, 0, 2, 0), played)
        val entry = log.entries.value.single()
        assertEquals(-now.toEpochMilliseconds(), entry.id)
        assertEquals("質問", entry.userText)
        assertEquals("答え", entry.text)
        assertEquals(ReplyLogEntry.SOURCE_GEMINI, entry.source)
        assertTrue(entry.played)
        assertContentEquals(pcm16MonoToWav(byteArrayOf(1, 0, 2, 0), 24000), log.readAudio(entry.id))
        assertEquals(listOf(GeminiTurn("質問", "答え")), history.turns.value)
        assertEquals(listOf("KEY"), connector.keys)
    }

    @Test
    fun missingKeyIsLoggedWithoutConnecting() = runTest {
        val connector = FakeConnector(mutableListOf())
        responder(connector).respond("file-1")

        assertTrue("API キー" in log.entries.value.single().text)
        assertTrue(connector.connections.isEmpty())
    }

    @Test
    fun failureIsLoggedAndLeavesHistoryUntouched() = runTest {
        tokens.saveToken("gemini_live_api_key", "KEY")
        responder(FakeConnector(mutableListOf(listOf("""{"error":{"message":"quota"}}""")))).respond("file-1")

        val entry = log.entries.value.single()
        assertTrue("quota" in entry.text)
        assertFalse(entry.hasAudio)
        assertTrue(history.turns.value.isEmpty())
    }

    @Test
    fun prewarmedSessionIsUsedForTheNextRecording() = runTest {
        tokens.saveToken("gemini_live_api_key", "KEY")
        val connector = FakeConnector(mutableListOf(replyScript("質問", "答え")))
        val sessions = manager(connector)
        sessions.prewarm()
        runCurrent()
        assertEquals(1, connector.connections.size)

        responder(connector, sessions = sessions).respond("file-1")

        assertEquals(1, connector.connections.size)
        assertEquals("答え", log.entries.value.single().text)
    }

    @Test
    fun unusedPrewarmedSessionIsClosed() = runTest {
        tokens.saveToken("gemini_live_api_key", "KEY")
        val connector = FakeConnector(mutableListOf(listOf(SETUP_COMPLETE)))
        manager(connector).prewarm()
        advanceTimeBy(31.seconds)
        runCurrent()

        assertTrue(connector.connections.single().closed)
    }

    @Test
    fun prewarmOnlyWhenAGestureRoutesToGeminiLive() = runTest {
        tokens.saveToken("gemini_live_api_key", "KEY")
        routing.setRoute(RingGesture.Hold, GestureDestination.IndexAgent)
        val connector = FakeConnector(mutableListOf())
        manager(connector).prewarm()
        runCurrent()

        assertTrue(connector.connections.isEmpty())
    }
}
