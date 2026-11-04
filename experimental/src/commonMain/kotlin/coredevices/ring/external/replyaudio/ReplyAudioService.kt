package coredevices.ring.external.replyaudio

import co.touchlab.kermit.Logger
import coredevices.ring.service.RecordingBackgroundScope
import coredevices.ring.util.AudioPlayer
import coredevices.ring.util.PlaybackState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.Buffer
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

fun interface ReplyAudioPlayer {
    /** Plays [audio] (M4A/MP3/WAV) and suspends until playback ends. */
    suspend fun play(audio: ByteArray)
}

expect fun createReplyAudioPlayer(): ReplyAudioPlayer

class AudioPlayerReplyAudioPlayer : ReplyAudioPlayer {
    private val player by lazy { AudioPlayer() }

    override suspend fun play(audio: ByteArray) {
        player.playAAC(Buffer().apply { write(audio) }, 0)
        // playAAC starts asynchronously; a decode failure never reaches Playing.
        withTimeoutOrNull(5.seconds) { player.playbackState.first { it is PlaybackState.Playing } }
            ?: return
        withTimeoutOrNull(3.minutes) { player.playbackState.first { it is PlaybackState.Stopped } }
    }
}

/**
 * Polls the reply server while the app runs, logs every reply and auto-plays fresh ones in order.
 */
class ReplyAudioService(
    private val preferences: ReplyAudioPreferences,
    private val api: ReplyAudioApi,
    private val log: ReplyAudioLog,
    private val player: ReplyAudioPlayer,
    private val scope: RecordingBackgroundScope,
    private val clock: Clock = Clock.System,
) {
    companion object {
        val POLL_INTERVAL = 3.seconds
        private val logger = Logger.withTag("ReplyAudioService")
    }

    private val playRequests = Channel<Long>(Channel.UNLIMITED)
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch { pollLoop() }
        scope.launch { for (id in playRequests) playNow(id) }
    }

    fun replay(id: Long) {
        playRequests.trySend(id)
    }

    private suspend fun pollLoop() {
        while (currentCoroutineContext().isActive) {
            val config = preferences.config.value
            if (config.isUsable) {
                try {
                    pollOnce(config)
                    _lastError.value = null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.w { "Reply poll failed: ${e.message}" }
                    _lastError.value = e.message ?: e::class.simpleName
                }
            }
            delay(POLL_INTERVAL)
        }
    }

    internal suspend fun pollOnce(config: ReplyAudioConfig) {
        val cursor = preferences.cursor
        val replies = ReplyAudioRules.newReplies(
            api.fetchReplies(config.url, config.token, cursor).replies,
            cursor,
        )
        for (reply in replies) {
            val now = clock.now()
            val entry = log.add(reply, now)
            preferences.cursor = reply.id
            logger.i { "Reply ${reply.id} received (request=${reply.requestId}, audio=${entry.hasAudio})" }
            if (ReplyAudioRules.shouldAutoPlay(entry, now)) playRequests.send(entry.id)
        }
    }

    private suspend fun playNow(id: Long) {
        try {
            val audio = log.readAudio(id) ?: return
            logger.i { "Reply $id playback start" }
            player.play(audio)
            log.markPlayed(id)
            logger.i { "Reply $id playback end" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(e) { "Reply $id playback failed" }
        }
    }
}
