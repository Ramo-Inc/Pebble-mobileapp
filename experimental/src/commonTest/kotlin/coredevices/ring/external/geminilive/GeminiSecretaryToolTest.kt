package coredevices.ring.external.geminilive

import com.russhwolf.settings.MapSettings
import coredevices.ring.external.replyaudio.ReplyAudioConfig
import coredevices.ring.external.replyaudio.ReplyAudioPreferences
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeminiSecretaryToolTest {
    private val preferences = ReplyAudioPreferences(MapSettings()).also {
        it.save(ReplyAudioConfig(url = "http://mac:8765/", token = "test-token", enabled = true))
    }
    private val call = GeminiFunctionCall("call-1", GeminiLiveProtocol.DELEGATE_FUNCTION, buildJsonObject {
        put("task", "今日のニュースを3件探して")
    })

    @Test
    fun sendsOnlyTheTaskToTheExistingSecretaryWebhookAndReturnsAnAcknowledgement() = runTest {
        var deliveries = 0
        val engine = MockEngine { request ->
            deliveries++
            assertEquals("http://mac:8765/webhook", request.url.toString())
            assertEquals("Bearer test-token", request.headers[HttpHeaders.Authorization])
            assertTrue(request.headers["X-Index-Delivery"]!!.startsWith("gemini-"))
            assertEquals("gemini-live", request.headers["X-Index-Trigger"])
            val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            assertTrue("name=\"transcription\"\r\n\r\n今日のニュースを3件探して\r\n" in body)
            assertFalse("name=\"audio\"" in body)
            respond("""{"status":"queued"}""")
        }

        val result = GeminiSecretaryTool(preferences, engine).execute(call)

        assertEquals(1, deliveries)
        assertEquals("accepted", result["status"]!!.jsonPrimitive.content)
        assertTrue("秘書" in result["message"]!!.jsonPrimitive.content)
        assertFalse("test-token" in result.toString())
    }

    @Test
    fun disabledOrMissingReplyServerDoesNotSubmitWork() = runTest {
        val engine = MockEngine { error("Must not send a task") }
        val tool = GeminiSecretaryTool(preferences, engine)
        preferences.save(ReplyAudioConfig(url = "http://mac:8765", enabled = false))
        assertTrue("error" in tool.execute(call))
        preferences.save(ReplyAudioConfig(url = "", enabled = true))
        assertTrue("error" in tool.execute(call))
    }

    @Test
    fun unknownFunctionAndEmptyTaskAreRejectedWithoutSending() = runTest {
        val tool = GeminiSecretaryTool(preferences, MockEngine { error("Must not send a task") })
        assertTrue("error" in tool.execute(call.copy(name = "other")))
        assertTrue("error" in tool.execute(call.copy(args = JsonObject(emptyMap()))))
        assertTrue("error" in tool.execute(call.copy(args = buildJsonObject { put("task", "  ") })))
        assertTrue("error" in tool.execute(call.copy(args = buildJsonObject { put("task", 42) })))
        assertTrue("error" in tool.execute(call.copy(args = buildJsonObject { put("task", JsonObject(emptyMap())) })))
    }

    @Test
    fun httpSuccessWithoutAQueuedTaskIsReportedAsFailure() = runTest {
        for (status in listOf("relay disabled", "no transcription", "voice queued", "ignored test event")) {
            val tool = GeminiSecretaryTool(preferences, MockEngine { respond("""{"status":"$status"}""") })
            val result = tool.execute(call)
            assertTrue("error" in result, status)
            assertFalse("status" in result)
        }
    }

    @Test
    fun httpFailureAndLostResponseAreNotReportedAsAcceptedOrRetried() = runTest {
        var attempts = 0
        val unauthorized = GeminiSecretaryTool(preferences, MockEngine {
            attempts++
            respond("unauthorized", HttpStatusCode.Unauthorized)
        })
        assertTrue("401" in unauthorized.execute(call)["error"]!!.jsonPrimitive.content)
        val disconnected = GeminiSecretaryTool(preferences, MockEngine {
            attempts++
            throw IllegalStateException("test-token")
        })
        val result = disconnected.execute(call)
        assertTrue("error" in result)
        assertFalse("test-token" in result.toString())
        assertEquals(2, attempts)
    }

    @Test
    fun cancellationPropagates() = runTest {
        val tool = GeminiSecretaryTool(preferences, MockEngine { throw CancellationException("cancelled") })
        assertFailsWith<CancellationException> { tool.execute(call) }
    }
}
