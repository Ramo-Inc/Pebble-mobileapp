package coredevices.ring.external.replyaudio

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPCMFormatFloat32
import platform.AVFAudio.AVAudioPlayerNode
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionDuckOthers
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.setActive
import platform.Foundation.NSError
import platform.posix.memcpy
import kotlin.time.Duration.Companion.seconds

actual fun createReplyStreamPlayer(): ReplyStreamPlayer = EngineReplyStreamPlayer()

/** Schedules ~0.1 s float buffers on an AVAudioPlayerNode as PCM arrives; same mixable session as file replies. */
private class EngineReplyStreamPlayer : ReplyStreamPlayer {

    override suspend fun playStream(sampleRate: Int, chunks: ReceiveChannel<ByteArray>) {
        val session = AVAudioSession.sharedInstance()
        val engine = AVAudioEngine()
        val node = AVAudioPlayerNode()
        val format = AVAudioFormat(AVAudioPCMFormatFloat32, sampleRate.toDouble(), 1u, false)
        withContext(Dispatchers.Main) {
            call("set audio category") { session.setCategory(AVAudioSessionCategoryPlayback, AVAudioSessionCategoryOptionDuckOthers, it) }
            call("activate audio session") { session.setActive(true, it) }
            engine.attachNode(node)
            engine.connect(node, engine.mainMixerNode, format)
            engine.prepare()
            call("start audio engine") { engine.startAndReturnError(it) }
        }
        val finished = Channel<Unit>(Channel.UNLIMITED)
        var scheduledBuffers = 0
        var scheduledFrames = 0L
        var started = false
        val framesPerBuffer = sampleRate / 10
        val prebufferFrames = (sampleRate * STREAM_PREBUFFER_SECONDS).toLong()
        val pending = PcmAccumulator()

        fun schedule(samples: FloatArray) {
            val buffer = AVAudioPCMBuffer(format, samples.size.toUInt()) ?: error("allocate audio buffer")
            memcpy(buffer.floatChannelData!![0]!!, samples.refTo(0), samples.size.toULong() * 4u)
            buffer.frameLength = samples.size.toUInt()
            node.scheduleBuffer(buffer) { finished.trySend(Unit) }
            scheduledBuffers++
            scheduledFrames += samples.size
            if (!started && scheduledFrames >= prebufferFrames) {
                node.play()
                started = true
            }
        }

        try {
            for (chunk in chunks) {
                pending.add(chunk)
                while (pending.frames >= framesPerBuffer) schedule(pending.take(framesPerBuffer))
            }
            if (pending.frames > 0) schedule(pending.take(pending.frames))
            if (scheduledBuffers == 0) return
            if (!started) node.play()
            withTimeoutOrNull((scheduledFrames.toDouble() / sampleRate).seconds + FINISH_GRACE) {
                repeat(scheduledBuffers) { finished.receive() }
            }
        } finally {
            node.stop()
            engine.stop()
            session.setActive(false, AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation, null)
        }
    }

    private fun call(what: String, block: (CPointer<ObjCObjectVar<NSError?>>) -> Boolean) = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        check(block(error.ptr)) { "Failed to $what: ${error.value?.localizedDescription}" }
    }

    private companion object {
        val FINISH_GRACE = 5.seconds
    }
}
