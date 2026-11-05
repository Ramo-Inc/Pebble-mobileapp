package coredevices.ring.external.replyaudio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

actual fun createReplyStreamPlayer(): ReplyStreamPlayer = AudioTrackReplyStreamPlayer()

private class AudioTrackReplyStreamPlayer : ReplyStreamPlayer {

    override suspend fun playStream(sampleRate: Int, chunks: ReceiveChannel<ByteArray>): Unit = withContext(Dispatchers.IO) {
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuffer, (sampleRate * 2 * STREAM_PREBUFFER_SECONDS).toInt()))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        var writtenFrames = 0L
        var carry: Byte? = null
        try {
            track.play()
            for (chunk in chunks) {
                // Keep writes sample-aligned when a chunk splits a 16-bit sample.
                val bytes = carry?.let { byteArrayOf(it) + chunk } ?: chunk
                val even = bytes.size - bytes.size % 2
                carry = if (even < bytes.size) bytes.last() else null
                if (even > 0) {
                    track.write(bytes, 0, even)
                    writtenFrames += even / 2
                }
            }
            val total = writtenFrames
            withTimeoutOrNull((total.toDouble() / sampleRate).seconds + 5.seconds) {
                while (track.playbackHeadPosition.toLong() < total) delay(50.milliseconds)
            }
            Unit
        } finally {
            track.stop()
            track.release()
        }
    }
}
