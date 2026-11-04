package coredevices.ring.external.replyaudio

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** One reply as served by the reply server's `GET /replies`. */
@Serializable
data class ReplyDto(
    val id: Long,
    @SerialName("request_id") val requestId: String? = null,
    val text: String = "",
    @SerialName("audio_base64") val audioBase64: String? = null,
    /** Unix time in milliseconds when the server created the reply. */
    @SerialName("created_at") val createdAt: Long? = null,
)

@Serializable
data class ReplyListResponse(
    val replies: List<ReplyDto> = emptyList(),
)

/** A reply kept in the on-device log. Audio bytes live in a separate file keyed by [id]. */
@Serializable
data class ReplyLogEntry(
    val id: Long,
    val requestId: String? = null,
    val text: String,
    val createdAtMs: Long,
    val receivedAtMs: Long,
    val hasAudio: Boolean,
    val played: Boolean = false,
) {
    val createdAt: Instant get() = Instant.fromEpochMilliseconds(createdAtMs)
}

object ReplyAudioRules {
    /** Replies older than this when received are logged but not auto-played, e.g. a backlog after a long outage. */
    val AUTO_PLAY_MAX_AGE: Duration = 15.minutes

    /** Replies after [cursor], each id once, oldest first. */
    fun newReplies(incoming: List<ReplyDto>, cursor: Long?): List<ReplyDto> =
        incoming
            .filter { cursor == null || it.id > cursor }
            .distinctBy { it.id }
            .sortedBy { it.id }

    fun shouldAutoPlay(entry: ReplyLogEntry, now: Instant): Boolean =
        entry.hasAudio && !entry.played && now - entry.createdAt <= AUTO_PLAY_MAX_AGE

    /** Newest first, capped at [max]. Returns the kept log and the ids that fell off the end. */
    fun prepend(log: List<ReplyLogEntry>, entry: ReplyLogEntry, max: Int): Pair<List<ReplyLogEntry>, List<Long>> {
        val merged = listOf(entry) + log.filter { it.id != entry.id }
        return merged.take(max) to merged.drop(max).map { it.id }
    }
}
