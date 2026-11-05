package coredevices.ring.external.geminilive

import coredevices.ring.external.indexwebhook.buildWebhookMultipartBody
import coredevices.ring.external.replyaudio.ReplyAudioPreferences
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.uuid.Uuid

class GeminiSecretaryTool(
    private val preferences: ReplyAudioPreferences,
    engine: HttpClientEngine,
    private val clock: Clock = Clock.System,
) {
    private val client = HttpClient(engine) { followRedirects = false }

    suspend fun execute(call: GeminiFunctionCall): JsonObject {
        if (call.name != GeminiLiveProtocol.DELEGATE_FUNCTION) return failure("未対応の機能です。")
        val task = (call.args["task"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()
        if (task.isNullOrEmpty()) return failure("依頼内容が空です。")
        val config = preferences.config.value
        if (!config.isUsable) return failure("Reply audio の Server URL と自動再生を設定してください。")

        val requestId = "gemini-${Uuid.random()}"
        val boundary = Uuid.random().toString()
        return try {
            val response = client.post("${config.url.trimEnd('/')}/webhook") {
                if (config.token.isNotBlank()) bearerAuth(config.token)
                header("X-Index-Delivery", requestId)
                header("X-Index-Webhook-Version", "1")
                header("X-Index-Trigger", "gemini-live")
                setBody(ByteArrayContent(
                    bytes = buildWebhookMultipartBody(
                        boundary = boundary,
                        audioData = null,
                        filename = "recording.m4a",
                        recordedAt = clock.now().toEpochMilliseconds(),
                        transcription = task,
                        isTest = false,
                    ),
                    contentType = ContentType.parse("multipart/form-data; boundary=$boundary"),
                ))
            }
            if (!response.status.isSuccess()) return failure("依頼サーバーが HTTP ${response.status.value} を返しました。")
            val status = Json.parseToJsonElement(response.bodyAsText()).jsonObject["status"]?.jsonPrimitive?.contentOrNull
            if (status != "queued" && status != "duplicate") return failure("依頼サーバーが秘書への転送を受け付けませんでした。")
            buildJsonObject {
                put("status", "accepted")
                put("request_id", requestId)
                put("message", "秘書への依頼を送信しました。これで受付を終了してください。以後の管理と音声での返答は秘書が行います。")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failure("依頼の受付を確認できませんでした。秘書からの返答や返事ログを確認してください。")
        }
    }

    private fun failure(message: String): JsonObject = buildJsonObject { put("error", message) }
}
