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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.Buffer
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

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
 * Polls the reply server while the app runs, logs every reply and auto-plays unplayed ones in order,
 * including the backlog whenever the app wakes up.
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
        /** A poll gap longer than this means iOS suspended the app; on resume the unplayed backlog is queued. */
        val WAKE_GAP = 10.seconds
        private val logger = Logger.withTag("ReplyAudioService")
    }

    private val playRequests = Channel<Long>(Channel.UNLIMITED)
    private val queued = MutableStateFlow<Set<Long>>(emptySet())
    private var lastPollAt: Instant? = null
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
                val now = clock.now()
                val woke = lastPollAt.let { it == null || now - it > WAKE_GAP }
                lastPollAt = now
                if (woke) queueUnplayed()
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
            val entry = log.add(reply, clock.now())
            preferences.cursor = reply.id
            logger.i { "Reply ${reply.id} received (request=${reply.requestId}, audio=${entry.hasAudio})" }
        }
        if (replies.isNotEmpty()) queueUnplayed()
    }

    /** Queues every unplayed reply with audio that is not already waiting, oldest first. */
    private suspend fun queueUnplayed() {
        val pending = log.entries.value.filter(ReplyAudioRules::shouldAutoPlay).map { it.id }.sorted()
        val fresh = pending.filterNot { it in queued.value }
        queued.update { it + fresh }
        for (id in fresh) playRequests.send(id)
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
        } finally {
            queued.update { it - id }
        }
    }
}
