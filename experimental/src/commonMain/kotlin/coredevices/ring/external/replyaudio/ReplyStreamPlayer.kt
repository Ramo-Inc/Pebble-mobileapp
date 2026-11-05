package coredevices.ring.external.replyaudio

import kotlinx.coroutines.channels.ReceiveChannel

/** Plays 16-bit little-endian mono PCM while it is still arriving. */
fun interface ReplyStreamPlayer {
    /** Suspends until every chunk received before [chunks] closed has been played. */
    suspend fun playStream(sampleRate: Int, chunks: ReceiveChannel<ByteArray>)
}

expect fun createReplyStreamPlayer(): ReplyStreamPlayer

/** Seconds of audio buffered before playback starts, so network jitter does not cause gaps. */
internal const val STREAM_PREBUFFER_SECONDS = 0.3

/** Wraps 16-bit mono PCM in a WAV header so the saved reply replays with the file player. */
fun pcm16MonoToWav(pcm: ByteArray, sampleRate: Int): ByteArray {
    val header = ByteArray(44)
    fun putAscii(offset: Int, text: String) = text.encodeToByteArray().copyInto(header, offset)
    fun putInt(offset: Int, value: Int) {
        for (i in 0 until 4) header[offset + i] = (value shr (8 * i)).toByte()
    }
    fun putShort(offset: Int, value: Int) {
        for (i in 0 until 2) header[offset + i] = (value shr (8 * i)).toByte()
    }
    putAscii(0, "RIFF")
    putInt(4, 36 + pcm.size)
    putAscii(8, "WAVE")
    putAscii(12, "fmt ")
    putInt(16, 16)
    putShort(20, 1)
    putShort(22, 1)
    putInt(24, sampleRate)
    putInt(28, sampleRate * 2)
    putShort(32, 2)
    putShort(34, 16)
    putAscii(36, "data")
    putInt(40, pcm.size)
    return header + pcm
}

/** Collects 16-bit little-endian bytes, which may split a sample across chunks, and hands out float frames. */
internal class PcmAccumulator {
    private var bytes = ByteArray(0)

    val frames: Int get() = bytes.size / 2

    fun add(chunk: ByteArray) {
        bytes += chunk
    }

    fun take(count: Int): FloatArray {
        val out = FloatArray(count) { i ->
            val sample = (bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)
            sample.toShort() / 32768f
        }
        bytes = bytes.copyOfRange(2 * count, bytes.size)
        return out
    }
}
