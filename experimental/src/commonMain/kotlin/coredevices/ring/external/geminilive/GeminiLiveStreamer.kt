package coredevices.ring.external.geminilive

import coredevices.ring.service.RecordingBackgroundScope
import coredevices.ring.service.button.GestureDestination
import coredevices.ring.service.button.GestureRoutingPreferences
import coredevices.ring.service.button.RingGesture
import coredevices.ring.service.parseAsButtonSequence
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Starts each ring recording's exchange with Gemini while the ring is still sending it. The gesture
 * is only known from the final part, so every recording is streamed while any gesture routes to
 * Gemini Live, and a recording whose gesture routes elsewhere is dropped without a reply.
 */
class GeminiLiveStreamer(
    private val responder: GeminiLiveResponder,
    private val routing: GestureRoutingPreferences,
    private val scope: RecordingBackgroundScope,
) {
    private companion object {
        const val KEPT_UTTERANCES = 8
    }

    private class Utterance(val startIndex: Int, val parts: Channel<RingAudioPart>, val answered: Deferred<Boolean>)

    private val mutex = Mutex()
    private val recent = ArrayDeque<Utterance>()
    private var current: Utterance? = null

    fun start(parts: Flow<RingAudioPart>) {
        scope.launch { parts.collect { onPart(it) } }
    }

    internal suspend fun onPart(part: RingAudioPart) {
        if (routing.routes.value.values.none { it == GestureDestination.GeminiLive }) return
        mutex.withLock {
            val utterance = current?.takeIf { it.startIndex == part.collectionStartIndex }
                ?: if (part.isFinal && (part.seconds < MIN_RECORDING_SECONDS || !routesToGemini(part))) return else begin(part.collectionStartIndex)
            utterance.parts.trySend(part)
            if (part.isFinal) {
                utterance.parts.close()
                current = null
            }
        }
    }

    private fun begin(startIndex: Int): Utterance {
        current?.parts?.close()
        val parts = Channel<RingAudioPart>(Channel.UNLIMITED)
        val answered = scope.async { responder.respondToParts(parts, ::routesToGemini) }
        val utterance = Utterance(startIndex, parts, answered)
        current = utterance
        recent.addLast(utterance)
        while (recent.size > KEPT_UTTERANCES) recent.removeFirst()
        return utterance
    }

    /** Whether the recording whose first part was [startIndex] was already answered from its streamed parts. */
    suspend fun answeredWhileStreaming(startIndex: Int): Boolean {
        val utterance = mutex.withLock {
            recent.firstOrNull { it.startIndex == startIndex }?.also { recent.remove(it) }
        } ?: return false
        return utterance.answered.await()
    }

    private fun routesToGemini(part: RingAudioPart): Boolean {
        val gesture = part.buttonSequence?.trim()?.parseAsButtonSequence()?.let { RingGesture.forRecordingSequence(it) }
        return routing.recordingDestinationFor(gesture) == GestureDestination.GeminiLive
    }
}
