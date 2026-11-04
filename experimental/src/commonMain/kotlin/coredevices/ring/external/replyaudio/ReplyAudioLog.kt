package coredevices.ring.external.replyaudio

import co.touchlab.kermit.Logger
import com.russhwolf.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.time.Instant

/**
 * On-device log of received replies: entries in settings, audio as one file per reply.
 * Capped at [MAX_ENTRIES], so it is stored in settings rather than costing a schema version.
 */
class ReplyAudioLog(
    private val settings: Settings,
    private val audioDirectory: () -> Path,
) {
    companion object {
        const val MAX_ENTRIES = 200
        private const val LOG_KEY = "reply_audio_log"
        private val logger = Logger.withTag("ReplyAudioLog")
        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = ListSerializer(ReplyLogEntry.serializer())
    }

    private val mutex = Mutex()
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<ReplyLogEntry>> = _entries.asStateFlow()

    suspend fun add(reply: ReplyDto, receivedAt: Instant): ReplyLogEntry = mutex.withLock {
        val audio = reply.audioBase64?.takeIf { it.isNotBlank() }?.let {
            try {
                Base64.decode(it)
            } catch (e: IllegalArgumentException) {
                logger.w { "Reply ${reply.id} has invalid audio_base64" }
                null
            }
        }
        if (audio != null) writeAudio(reply.id, audio)
        val entry = ReplyLogEntry(
            id = reply.id,
            requestId = reply.requestId,
            text = reply.text,
            createdAtMs = reply.createdAt ?: receivedAt.toEpochMilliseconds(),
            receivedAtMs = receivedAt.toEpochMilliseconds(),
            hasAudio = audio != null,
        )
        val (kept, dropped) = ReplyAudioRules.prepend(_entries.value, entry, MAX_ENTRIES)
        dropped.forEach { deleteAudio(it) }
        persist(kept)
        entry
    }

    suspend fun markPlayed(id: Long) = mutex.withLock {
        persist(_entries.value.map { if (it.id == id) it.copy(played = true) else it })
    }

    suspend fun readAudio(id: Long): ByteArray? = withContext(Dispatchers.IO) {
        val path = audioPath(id)
        if (!SystemFileSystem.exists(path)) return@withContext null
        SystemFileSystem.source(path).buffered().use { it.readByteArray() }
    }

    private suspend fun writeAudio(id: Long, bytes: ByteArray) = withContext(Dispatchers.IO) {
        SystemFileSystem.createDirectories(audioDirectory(), false)
        SystemFileSystem.sink(audioPath(id)).buffered().use { it.write(bytes) }
    }

    private suspend fun deleteAudio(id: Long) = withContext(Dispatchers.IO) {
        SystemFileSystem.delete(audioPath(id), mustExist = false)
    }

    private fun audioPath(id: Long) = Path(audioDirectory(), "$id.audio")

    private fun persist(entries: List<ReplyLogEntry>) {
        settings.putString(LOG_KEY, json.encodeToString(serializer, entries))
        _entries.value = entries
    }

    private fun load(): List<ReplyLogEntry> {
        val raw = settings.getStringOrNull(LOG_KEY) ?: return emptyList()
        return try {
            json.decodeFromString(serializer, raw)
        } catch (e: Exception) {
            logger.w(e) { "Discarding unreadable reply log" }
            emptyList()
        }
    }
}
