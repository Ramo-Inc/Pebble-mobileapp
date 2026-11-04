package coredevices.ring.external.replyaudio

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioPlayerDelegateProtocol
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionDuckOthers
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.setActive
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.create
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.seconds

actual fun createReplyAudioPlayer(): ReplyAudioPlayer = SessionReplyAudioPlayer()

/**
 * iOS refuses to activate a non-mixable session from the background, so replies use a
 * mixable (ducking) session and fail loudly when playback cannot start.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class SessionReplyAudioPlayer : ReplyAudioPlayer {
    // AVAudioPlayer holds its delegate weakly; keep both alive until playback ends.
    private var active: Pair<AVAudioPlayer, FinishDelegate>? = null

    override suspend fun play(audio: ByteArray) = withContext(Dispatchers.Main) {
        require(audio.isNotEmpty()) { "empty audio" }
        val session = AVAudioSession.sharedInstance()
        call("set audio category") { session.setCategory(AVAudioSessionCategoryPlayback, AVAudioSessionCategoryOptionDuckOthers, it) }
        call("activate audio session") { session.setActive(true, it) }
        try {
            val data = audio.usePinned { NSData.create(bytes = it.addressOf(0), length = audio.size.toULong()) }
            val player = memScoped {
                val error = alloc<ObjCObjectVar<NSError?>>()
                AVAudioPlayer(data = data, error = error.ptr) ?: error("decode audio: ${error.value?.localizedDescription}")
            }
            val delegate = FinishDelegate()
            player.delegate = delegate
            active = player to delegate
            // The finish callback is not guaranteed, so never hold the queue longer than the clip.
            withTimeoutOrNull(player.duration.seconds + FINISH_GRACE) {
                suspendCancellableCoroutine { cont ->
                    delegate.onFinish = { ok ->
                        if (cont.isActive) {
                            if (ok) cont.resume(Unit) else cont.resumeWithException(IllegalStateException("playback did not finish"))
                        }
                    }
                    cont.invokeOnCancellation { player.stop() }
                    if (!player.play()) cont.resumeWithException(IllegalStateException("AVAudioPlayer refused to play"))
                }
            } ?: player.stop()
        } finally {
            active = null
            session.setActive(false, AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation, null)
        }
    }

    private companion object {
        val FINISH_GRACE = 5.seconds
    }

    private fun call(what: String, block: (CPointer<ObjCObjectVar<NSError?>>) -> Boolean) = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        check(block(error.ptr)) { "Failed to $what: ${error.value?.localizedDescription}" }
    }

    private class FinishDelegate : NSObject(), AVAudioPlayerDelegateProtocol {
        var onFinish: (Boolean) -> Unit = {}

        override fun audioPlayerDidFinishPlaying(player: AVAudioPlayer, successfully: Boolean) = onFinish(successfully)

        override fun audioPlayerDecodeErrorDidOccur(player: AVAudioPlayer, error: NSError?) = onFinish(false)
    }
}
