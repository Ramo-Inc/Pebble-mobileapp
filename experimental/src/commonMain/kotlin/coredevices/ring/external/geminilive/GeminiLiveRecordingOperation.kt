package coredevices.ring.external.geminilive

import coredevices.libindex.database.repository.RingTransferRepository
import coredevices.ring.service.recordings.RecordingProcessingQueue
import coredevices.ring.service.recordings.button.RecordingOperation
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** A recording routed to Gemini Live: no transcription or agent, just the spoken exchange. */
class GeminiLiveRecordingOperation(private val fileId: String, private val transferId: Long?) : RecordingOperation, KoinComponent {
    private val responder: GeminiLiveResponder by inject()
    private val streamer: GeminiLiveStreamer by inject()
    private val transfers: RingTransferRepository by inject()

    override suspend fun run(handle: RecordingProcessingQueue.TaskHandle?) {
        val startIndex = transferId?.let { transfers.getRingTransferById(it)?.transferInfo?.collectionStartIndex }
        if (startIndex != null && streamer.answeredWhileStreaming(startIndex)) return
        responder.respond(fileId)
    }
}
