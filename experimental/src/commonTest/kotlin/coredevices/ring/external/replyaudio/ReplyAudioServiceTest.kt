package coredevices.ring.external.replyaudio

import com.russhwolf.settings.MapSettings
import coredevices.ring.service.RecordingBackgroundScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ReplyAudioServiceTest {

    private val now = Instant.fromEpochMilliseconds(1_800_000_000_000)
    private val clock = object : Clock {
        override fun now() = now
    }
    private val audioBytes = byteArrayOf(1, 2, 3, 4)
    private val audioBase64 = Base64.encode(audioBytes)

    private class FakeApi : ReplyAudioApi {
        var replies: List<ReplyDto> = emptyList()
        val requestedCursors = mutableListOf<Long?>()
        override suspend fun fetchReplies(baseUrl: String, token: String, after: Long?): ReplyListResponse {
            requestedCursors += after
            return ReplyListResponse(replies.filter { after == null || it.id > after })
        }
    }

    private val settings = MapSettings()
    private val preferences = ReplyAudioPreferences(settings).also {
        it.save(ReplyAudioConfig(url = "http://mac:8765", token = "t", enabled = true))
    }
    private val audioDir = Path(SystemTemporaryDirectory, "reply-audio-test-${Uuid.random()}")
    private val log = ReplyAudioLog(settings) { audioDir }
    private val api = FakeApi()

    private fun TestScope.service() = ReplyAudioService(
        preferences = preferences,
        api = api,
        log = log,
        player = { },
        scope = RecordingBackgroundScope(backgroundScope),
        clock = clock,
    )

    private fun reply(id: Long, createdAt: Instant = now, audio: String? = audioBase64) =
        ReplyDto(id = id, requestId = "rec-1", text = "reply $id", audioBase64 = audio, createdAt = createdAt.toEpochMilliseconds())

    @Test
    fun pollLogsRepliesAndAdvancesCursor() = runTest {
        api.replies = listOf(reply(2), reply(1))
        service().pollOnce(preferences.config.value)

        assertEquals(listOf(2L, 1L), log.entries.value.map { it.id })
        assertEquals(2L, preferences.cursor)
        assertContentEquals(audioBytes, log.readAudio(1))
    }

    @Test
    fun secondPollOnlyFetchesNewReplies() = runTest {
        val service = service()
        api.replies = listOf(reply(1))
        service.pollOnce(preferences.config.value)
        api.replies = listOf(reply(1), reply(2))
        service.pollOnce(preferences.config.value)

        assertEquals(listOf(null, 1L), api.requestedCursors)
        assertEquals(listOf(2L, 1L), log.entries.value.map { it.id })
    }

    @Test
    fun replyWithoutAudioIsLoggedAsTextOnly() = runTest {
        api.replies = listOf(reply(1, audio = null))
        service().pollOnce(preferences.config.value)

        assertFalse(log.entries.value.single().hasAudio)
        assertNull(log.readAudio(1))
    }

    @Test
    fun changingServerUrlResetsCursor() {
        preferences.cursor = 5
        preferences.save(ReplyAudioConfig(url = "http://other:8765", token = "t", enabled = true))
        assertNull(preferences.cursor)
    }

    @Test
    fun logKeepsOnlyTheNewestEntriesAndDeletesTheirAudio() = runTest {
        repeat(ReplyAudioLog.MAX_ENTRIES + 1) { log.add(reply(it.toLong() + 1), now) }

        assertEquals(ReplyAudioLog.MAX_ENTRIES, log.entries.value.size)
        assertEquals(ReplyAudioLog.MAX_ENTRIES.toLong() + 1, log.entries.value.first().id)
        assertNull(log.readAudio(1))
        assertTrue(SystemFileSystem.exists(Path(audioDir, "2.audio")))
    }

    @Test
    fun logSurvivesReload() = runTest {
        log.add(reply(1), now)
        log.markPlayed(1)

        val reloaded = ReplyAudioLog(settings) { audioDir }
        assertTrue(reloaded.entries.value.single().played)
    }
}

class ReplyAudioRulesTest {

    private val now = Instant.fromEpochMilliseconds(1_800_000_000_000)

    private fun entry(createdAt: Instant, hasAudio: Boolean = true, played: Boolean = false) = ReplyLogEntry(
        id = 1,
        text = "t",
        createdAtMs = createdAt.toEpochMilliseconds(),
        receivedAtMs = now.toEpochMilliseconds(),
        hasAudio = hasAudio,
        played = played,
    )

    @Test
    fun newRepliesAreAfterCursorDedupedAndOldestFirst() {
        val incoming = listOf(ReplyDto(id = 3), ReplyDto(id = 1), ReplyDto(id = 2), ReplyDto(id = 3))
        assertEquals(listOf(2L, 3L), ReplyAudioRules.newReplies(incoming, cursor = 1).map { it.id })
        assertEquals(listOf(1L, 2L, 3L), ReplyAudioRules.newReplies(incoming, cursor = null).map { it.id })
    }

    @Test
    fun freshReplyWithAudioAutoPlays() {
        assertTrue(ReplyAudioRules.shouldAutoPlay(entry(now - 1.minutes), now))
    }

    @Test
    fun staleReplyIsNotAutoPlayed() {
        assertFalse(ReplyAudioRules.shouldAutoPlay(entry(now - ReplyAudioRules.AUTO_PLAY_MAX_AGE - 1.minutes), now))
    }

    @Test
    fun textOnlyOrPlayedReplyIsNotAutoPlayed() {
        assertFalse(ReplyAudioRules.shouldAutoPlay(entry(now, hasAudio = false), now))
        assertFalse(ReplyAudioRules.shouldAutoPlay(entry(now, played = true), now))
    }
}
