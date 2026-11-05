package coredevices.ring.external.geminilive

import co.touchlab.kermit.Logger
import coredevices.ring.external.replyaudio.ReplyAudioLog
import coredevices.ring.external.replyaudio.ReplyPlaybackLock
import coredevices.ring.external.replyaudio.ReplyStreamPlayer
import coredevices.ring.external.replyaudio.pcm16MonoToWav
import coredevices.ring.service.RecordingBackgroundScope
import coredevices.ring.service.RingEvent
import coredevices.ring.service.button.GestureDestination
import coredevices.ring.service.button.GestureRoutingPreferences
import coredevices.ring.storage.RecordingStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Opens the Gemini session while the ring is still transferring, so the connection and setup
 * (about 0.75 s) are done by the time the recording is ready.
 */
class GeminiLiveSessionManager(
    private val connector: GeminiLiveConnector,
    private val keyStorage: GeminiLiveKeyStorage,
    private val history: GeminiConversationHistory,
    private val routing: GestureRoutingPreferences,
    private val scope: RecordingBackgroundScope,
    private val idleTimeout: Duration = 30.seconds,
) {
    private class Prepared(val session: Deferred<GeminiLiveSession>, val history: List<GeminiTurn>, val expiry: Job)

    private val mutex = Mutex()
    private var prepared: Prepared? = null

    fun start(ringEvents: Flow<RingEvent>) {
        scope.launch {
            ringEvents.filterIsInstance<RingEvent.Transfer.InProgress>().collect { prewarm() }
        }
    }

    suspend fun prewarm() {
        if (routing.routes.value.values.none { it == GestureDestination.GeminiLive }) return
        val apiKey = keyStorage.get() ?: return
        mutex.withLock {
            if (prepared != null) return
            val turns = history.turns.value
            val session = scope.async { GeminiLiveSession.open(connector, apiKey, turns) }
            lateinit var entry: Prepared
            val expiry = scope.launch {
                delay(idleTimeout)
                if (mutex.withLock { (prepared === entry).also { if (it) prepared = null } }) discard(entry.session)
            }
            entry = Prepared(session, turns, expiry)
            prepared = entry
        }
    }

    /** The prewarmed session when it opened with the current history, otherwise a new one. */
    suspend fun take(apiKey: String): GeminiLiveSession {
        val ready = mutex.withLock { prepared.also { prepared = null } }
        val turns = history.turns.value
        if (ready != null) {
            ready.expiry.cancel()
            if (ready.history == turns) {
                try {
                    return ready.session.await()
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    logger.w { "Prewarmed Gemini session failed, connecting again: ${e.message}" }
                }
            } else {
                scope.launch { discard(ready.session) }
            }
        }
        return GeminiLiveSession.open(connector, apiKey, turns)
    }

    private suspend fun discard(session: Deferred<GeminiLiveSession>) {
        try {
            session.await().close()
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
        }
    }

    private companion object {
        val logger = Logger.withTag("GeminiLiveSession")
    }
}

/** One spoken exchange: send the recording to Gemini, play the reply as it streams in, then log it. */
class GeminiLiveResponder(
    private val sessions: GeminiLiveSessionManager,
    private val keyStorage: GeminiLiveKeyStorage,
    private val history: GeminiConversationHistory,
    private val replyLog: ReplyAudioLog,
    private val streamPlayer: ReplyStreamPlayer,
    private val playbackLock: ReplyPlaybackLock,
    private val readRecording: suspend (fileId: String) -> Pair<ByteArray, Int>,
    private val clock: Clock = Clock.System,
) {
    private companion object {
        val logger = Logger.withTag("GeminiLive")
    }

    suspend fun respond(fileId: String) {
        val apiKey = keyStorage.get()
        if (apiKey == null) {
            replyLog.addLocal(userText = null, text = "Gemini Live の API キーが未設定です（ボタン設定で入力してください）", audio = null, at = clock.now())
            return
        }
        val reply = try {
            val (pcm, sampleRate) = readRecording(fileId)
            playbackLock.mutex.withLock {
                val session = sessions.take(apiKey)
                logger.i { "Recording $fileId sent to Gemini (${pcm.size} bytes)" }
                streamAndPlay(session, pcm, sampleRate)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(e) { "Gemini Live failed for $fileId" }
            replyLog.addLocal(userText = null, text = "Gemini Live で返事を作れませんでした（${e.message}）", audio = null, at = clock.now())
            return
        }
        logger.i { "Gemini reply for $fileId: ${reply.replyText.take(60)}" }
        val wav = reply.audio.takeIf { it.isNotEmpty() }?.let { pcm16MonoToWav(it, reply.sampleRate) }
        replyLog.addLocal(userText = reply.userText, text = reply.replyText, audio = wav, at = clock.now(), played = wav != null)
        history.append(GeminiTurn(reply.userText, reply.replyText))
    }

    private suspend fun streamAndPlay(session: GeminiLiveSession, pcm: ByteArray, sampleRate: Int): GeminiReply = coroutineScope {
        val chunks = Channel<ByteArray>(Channel.UNLIMITED)
        var playback: Job? = null
        val reply = try {
            session.respond(pcm, sampleRate) { chunk, outputRate ->
                if (playback == null) {
                    logger.i { "First Gemini audio, playback starts" }
                    playback = launch { streamPlayer.playStream(outputRate, chunks) }
                }
                chunks.send(chunk)
            }
        } finally {
            chunks.close()
        }
        playback?.join()
        reply
    }
}

/** The preprocessed recording as 16-bit mono PCM and its sample rate, as the transcription reads it. */
suspend fun RecordingStorage.readPcm(fileId: String): Pair<ByteArray, Int> {
    val (source, meta) = openRecordingSource(fileId)
    val bytes = source.buffered().use { it.readByteArray() }
    return bytes to meta.cachedMetadata.sampleRate
}
