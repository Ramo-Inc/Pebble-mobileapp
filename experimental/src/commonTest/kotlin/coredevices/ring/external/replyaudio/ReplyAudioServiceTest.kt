package coredevices.ring.external.replyaudio

import com.russhwolf.settings.MapSettings
import coredevices.ring.service.RecordingBackgroundScope
import kotlinx.coroutines.flow.first
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
    private var clockNow = now
    private val clock = object : Clock {
        override fun now() = clockNow
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

    private fun TestScope.service(player: ReplyAudioPlayer = ReplyAudioPlayer { }) = ReplyAudioService(
        preferences = preferences,
        api = api,
        log = log,
        player = player,
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

    @Test
    fun wakingUpPlaysTheWholeUnplayedBacklogOldestFirst() = runTest {
        fun withOwnAudio(id: Long, createdAt: Instant = now) = reply(id, createdAt, Base64.encode(byteArrayOf(id.toByte())))
        log.add(withOwnAudio(1, createdAt = now - 300.minutes), now - 300.minutes)
        log.add(reply(2, audio = null), now)
        log.add(withOwnAudio(3), now)
        log.markPlayed(3)
        log.add(withOwnAudio(4), now)
        preferences.cursor = 4
        val played = mutableListOf<Byte>()
        service(player = { played += it.single() }).start()

        val entries = log.entries.first { entries -> entries.filter { it.hasAudio }.all { it.played } }
        assertEquals(listOf<Byte>(1, 4), played)
        assertEquals(mapOf(1L to true, 2L to false, 3L to true, 4L to true), entries.associate { it.id to it.played })
    }

    @Test
    fun failedPlaybackStaysUnplayedAndDoesNotBlockTheNextReply() = runTest {
        var calls = 0
        api.replies = listOf(reply(2), reply(1))
        service(player = { if (calls++ == 0) error("refused to play") }).start()

        val entries = log.entries.first { entries -> entries.any { it.played } }
        assertEquals(2, calls)
        assertEquals(mapOf(1L to false, 2L to true), entries.associate { it.id to it.played })
    }
}

class ReplyAudioRulesTest {

    private val now = Instant.fromEpochMilliseconds(1_800_000_000_000)

    private fun entry(createdAt: Instant, hasAudio: Boolean = true, played: Boolean = false, id: Long = 1) = ReplyLogEntry(
        id = id,
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
    fun unplayedReplyWithAudioAutoPlaysWhateverItsAge() {
        assertTrue(ReplyAudioRules.shouldAutoPlay(entry(now - 1.minutes)))
        assertTrue(ReplyAudioRules.shouldAutoPlay(entry(now - 300.minutes)))
    }

    @Test
    fun textOnlyOrPlayedReplyIsNotAutoPlayed() {
        assertFalse(ReplyAudioRules.shouldAutoPlay(entry(now, hasAudio = false)))
        assertFalse(ReplyAudioRules.shouldAutoPlay(entry(now, played = true)))
    }

    @Test
    fun feedPreviewShowsNewestFewUntilExpanded() {
        val log = (12L downTo 1L).map { entry(now, id = it) }

        assertEquals(listOf(12L, 11L, 10L, 9L, 8L), ReplyAudioRules.feedPreview(log, expanded = false).map { it.id })
        assertEquals(log, ReplyAudioRules.feedPreview(log, expanded = true))
        assertEquals(log.take(2), ReplyAudioRules.feedPreview(log.take(2), expanded = false))
    }
}
