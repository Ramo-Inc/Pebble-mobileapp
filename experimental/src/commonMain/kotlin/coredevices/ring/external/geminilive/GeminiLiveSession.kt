package coredevices.ring.external.geminilive

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.http.encodeURLParameter
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.minutes

/** A text-message pipe to the Live API; the Ktor WebSocket in the app, a fake in tests. */
interface GeminiLiveConnection {
    suspend fun send(message: String)

    /** Text of each incoming message (the API sends JSON as text or binary frames). */
    val incoming: ReceiveChannel<String>

    suspend fun close()
}

fun interface GeminiLiveConnector {
    suspend fun connect(apiKey: String): GeminiLiveConnection
}

class KtorGeminiLiveConnector(engine: HttpClientEngine, private val scope: CoroutineScope) : GeminiLiveConnector {
    private val client = HttpClient(engine) { install(WebSockets) }

    private companion object {
        val logger = Logger.withTag("GeminiLive")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun connect(apiKey: String): GeminiLiveConnection {
        val session = client.webSocketSession("${GeminiLiveProtocol.URL}?key=${apiKey.encodeURLParameter()}")
        val messages = scope.produce {
            // A dropped socket ends the stream; the session reports it as a closed connection.
            try {
                session.incoming.consumeEach { frame ->
                    when (frame) {
                        is Frame.Text -> send(frame.readText())
                        is Frame.Binary -> send(frame.data.decodeToString())
                        else -> Unit
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w { "Gemini Live socket closed: ${e.message}" }
            }
        }
        return object : GeminiLiveConnection {
            override suspend fun send(message: String) = session.send(Frame.Text(message))
            override val incoming = messages
            override suspend fun close() = session.close()
        }
    }
}

class GeminiLiveException(message: String) : Exception(message)

data class GeminiReply(val userText: String, val replyText: String, val audio: ByteArray, val sampleRate: Int)

/** One utterance and its reply over a connection whose setup (history included) is already done. */
class GeminiLiveSession private constructor(private val connection: GeminiLiveConnection) {

    companion object {
        private const val AUDIO_CHUNK_SECONDS = 1
        private val MAX_WAIT = 2.minutes

        /** Connects and sends the setup; returns once the server has accepted it. */
        suspend fun open(connector: GeminiLiveConnector, apiKey: String, history: List<GeminiTurn>): GeminiLiveSession {
            val connection = connector.connect(apiKey)
            try {
                connection.send(GeminiLiveProtocol.setup(history))
                return withinMaxWait("setup") { connection.awaitSetup() }
            } catch (e: Throwable) {
                connection.close()
                throw e
            }
        }

        private suspend fun GeminiLiveConnection.awaitSetup(): GeminiLiveSession {
            while (true) {
                when (val event = nextEvent()) {
                    GeminiServerEvent.SetupComplete -> return GeminiLiveSession(this)
                    is GeminiServerEvent.Error -> throw GeminiLiveException(event.message)
                    else -> Unit
                }
            }
        }

        /** Bounds how long a session stays open when Gemini never answers or never ends its turn. */
        private suspend fun <T : Any> withinMaxWait(stage: String, block: suspend () -> T): T =
            withTimeoutOrNull(MAX_WAIT) { block() } ?: throw GeminiLiveException("Gemini did not finish the $stage within $MAX_WAIT")

        private suspend fun GeminiLiveConnection.nextEvent(): GeminiServerEvent? {
            val message = incoming.receiveCatching().getOrNull()
                ?: throw GeminiLiveException("Gemini closed the connection")
            return GeminiLiveProtocol.parse(message)
        }
    }

    /** Sends [pcm] (16-bit mono at [sampleRate]) and returns the reply; [onAudio] gets each chunk as it arrives. */
    suspend fun respond(pcm: ByteArray, sampleRate: Int, onAudio: suspend (ByteArray, Int) -> Unit): GeminiReply {
        try {
            beginUtterance()
            sendAudio(pcm, sampleRate)
        } catch (e: Throwable) {
            close()
            throw e
        }
        return endUtterance(onAudio)
    }

    suspend fun beginUtterance() = connection.send(GeminiLiveProtocol.activityStart())

    /** Sends more of the utterance (16-bit mono at [sampleRate]); can be called as the audio arrives. */
    suspend fun sendAudio(pcm: ByteArray, sampleRate: Int) {
        val chunkBytes = sampleRate * 2 * AUDIO_CHUNK_SECONDS
        for (start in pcm.indices step chunkBytes) {
            connection.send(GeminiLiveProtocol.audio(pcm.copyOfRange(start, minOf(start + chunkBytes, pcm.size)), sampleRate))
        }
    }

    /** Marks the utterance as finished and returns the reply, closing the session either way. */
    suspend fun endUtterance(onAudio: suspend (ByteArray, Int) -> Unit): GeminiReply {
        try {
            connection.send(GeminiLiveProtocol.activityEnd())
            return withinMaxWait("reply") { receiveReply(onAudio) }
        } finally {
            close()
        }
    }

    private suspend fun receiveReply(onAudio: suspend (ByteArray, Int) -> Unit): GeminiReply {
        val userText = StringBuilder()
        val replyText = StringBuilder()
        val audio = mutableListOf<ByteArray>()
        var rate = GeminiLiveProtocol.OUTPUT_SAMPLE_RATE
        while (true) {
            when (val event = connection.nextEvent()) {
                is GeminiServerEvent.Error -> throw GeminiLiveException(event.message)
                is GeminiServerEvent.Content -> {
                    userText.append(event.inputText)
                    replyText.append(event.outputText)
                    rate = event.sampleRate
                    for (chunk in event.audio) {
                        audio += chunk
                        onAudio(chunk, rate)
                    }
                    if (event.turnComplete) {
                        val all = ByteArray(audio.sumOf { it.size })
                        var offset = 0
                        for (chunk in audio) {
                            chunk.copyInto(all, offset)
                            offset += chunk.size
                        }
                        return GeminiReply(userText.toString().trim(), replyText.toString().trim(), all, rate)
                    }
                }
                else -> Unit
            }
        }
    }

    suspend fun close() = connection.close()
}
