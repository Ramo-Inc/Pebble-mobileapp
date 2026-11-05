package coredevices.ring.external.geminilive

import co.touchlab.kermit.Logger
import coredevices.haversine.KMPHaversineSatelliteManager
import coredevices.haversine.ppcommon.PPCollection
import coredevices.ring.service.RecordingBackgroundScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/** One piece of a ring recording, decoded as soon as the ring sends it, which is often while the button is still held. */
class RingAudioPart(
    val collectionStartIndex: Int,
    val index: Int,
    val samples: ShortArray,
    val sampleRate: Int,
    val isFinal: Boolean,
    val buttonSequence: String?,
) {
    val seconds: Double get() = samples.size.toDouble() / sampleRate
}

/** Shorter recordings are button presses, which RingSync drops too. */
internal const val MIN_RECORDING_SECONDS = 1.0

/**
 * Wraps the ring library's collection callback so [onCollection] also gets each raw collection,
 * after the library has handled it. The library keeps doing the transfer exactly as before.
 */
internal expect fun installCollectionTap(manager: KMPHaversineSatelliteManager, onCollection: (index: Int, data: ByteArray) -> Unit)

/** Audio parts of ring recordings as they arrive, ahead of the transfer completing. */
class RingAudioTap(private val manager: KMPHaversineSatelliteManager, private val scope: RecordingBackgroundScope) {
    private companion object {
        val logger = Logger.withTag("RingAudioTap")
    }

    private val collections = Channel<Pair<Int, ByteArray>>(Channel.UNLIMITED)
    private val _parts = MutableSharedFlow<RingAudioPart>(extraBufferCapacity = 256)
    val parts: SharedFlow<RingAudioPart> = _parts.asSharedFlow()

    fun start() {
        installCollectionTap(manager) { index, data -> collections.trySend(index to data) }
        scope.launch {
            for ((index, data) in collections) {
                val part = try {
                    decodeRingAudioPart(index, data)
                } catch (e: Exception) {
                    logger.w(e) { "Could not decode collection $index" }
                    null
                } ?: continue
                logger.d { "Part ${part.index} of ${part.collectionStartIndex}: ${part.samples.size} samples at ${part.sampleRate} Hz, final=${part.isFinal}, sequence=${part.buttonSequence}" }
                _parts.emit(part)
            }
        }
    }
}

internal fun decodeRingAudioPart(index: Int, data: ByteArray): RingAudioPart? {
    val collection = PPCollection(index, data)
    val timeline = collection.audioTimeline ?: return null
    return RingAudioPart(
        collectionStartIndex = if (timeline.isMultiPart) timeline.collectionStartIndex.toInt() else index,
        index = index,
        samples = timeline.samples,
        sampleRate = timeline.sampleRateHz.toInt(),
        isFinal = !timeline.isMultiPart || timeline.isFinalPart,
        buttonSequence = collection.buttonPressSequence,
    )
}

internal fun ShortArray.toPcm16Le(): ByteArray {
    val bytes = ByteArray(size * 2)
    for (i in indices) {
        val sample = this[i].toInt()
        bytes[i * 2] = sample.toByte()
        bytes[i * 2 + 1] = (sample shr 8).toByte()
    }
    return bytes
}
