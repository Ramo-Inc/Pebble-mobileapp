package coredevices.ring.external.geminilive

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.io.encoding.Base64

/** One past exchange, fed back to Gemini as context. */
@Serializable
data class GeminiTurn(val user: String, val assistant: String)

data class GeminiFunctionCall(val id: String, val name: String, val args: JsonObject)

sealed interface GeminiServerEvent {
    data object SetupComplete : GeminiServerEvent

    data class Content(
        val audio: List<ByteArray> = emptyList(),
        val sampleRate: Int = GeminiLiveProtocol.OUTPUT_SAMPLE_RATE,
        val inputText: String = "",
        val outputText: String = "",
        val turnComplete: Boolean = false,
    ) : GeminiServerEvent

    data class Error(val message: String) : GeminiServerEvent

    data class ToolCall(val calls: List<GeminiFunctionCall>) : GeminiServerEvent
}

/** Messages of the Gemini Live API (BidiGenerateContent), matching tools/reply-server/gemini_live.py. */
object GeminiLiveProtocol {
    const val URL =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    const val MODEL = "gemini-3.8-live"
    const val OUTPUT_SAMPLE_RATE = 24000
    const val DELEGATE_FUNCTION = "delegate_to_secretary"
    const val SYSTEM_PROMPT =
        "あなたは、ユーザーが指輪型デバイス越しに話しかける一次受付です。" +
            "日本語で、話し言葉で、短く（だいたい3文以内で）答えてください。" +
            "記号や箇条書き、URLは読み上げに向かないので使わないでください。" +
            "調べもの、ニュース、検索、作業、記録、リマインダーなど、実行する仕事の依頼はすべてdelegate_to_secretaryで秘書に渡してください。" +
            "仕事は自分で実行せず、検索結果や完了報告も作らないでください。" +
            "taskには会話の文脈を補い、秘書が単独で理解できる依頼文を入れてください。複数の作業も一度の依頼にまとめてください。" +
            "受付が成功したら『秘書に依頼を投げました』とだけ伝えて終了してください。結果は待たず、進捗・確認・結果の返答は秘書に任せます。" +
            "受付が失敗したら依頼できなかったことと理由を短く伝え、送ったとは言わないでください。" +
            "雑談と依頼内容の聞き返しには自分で答えて構いません。過去の会話にある依頼を再送しないでください。"

    private val json = Json { ignoreUnknownKeys = true }

    fun setup(history: List<GeminiTurn>, model: String = MODEL): String = buildJsonObject {
        putJsonObject("setup") {
            put("model", if (model.startsWith("models/")) model else "models/$model")
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") { add(JsonPrimitive("AUDIO")) }
            }
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { addJsonObject { put("text", SYSTEM_PROMPT + historyText(history)) } }
            }
            // The whole utterance is already recorded, so its start and end are marked explicitly.
            putJsonObject("realtimeInputConfig") {
                putJsonObject("automaticActivityDetection") { put("disabled", true) }
            }
            putJsonObject("inputAudioTranscription") {}
            putJsonObject("outputAudioTranscription") {}
            putJsonArray("tools") {
                addJsonObject {
                    putJsonArray("functionDeclarations") {
                        addJsonObject {
                            put("name", DELEGATE_FUNCTION)
                            put("description", "ユーザーの仕事を秘書へ依頼する。受付だけを返し、進捗・確認・結果は秘書が別途音声で届ける。")
                            putJsonObject("parameters") {
                                put("type", "OBJECT")
                                putJsonObject("properties") {
                                    putJsonObject("task") {
                                        put("type", "STRING")
                                        put("description", "文脈、対象、条件を含む、秘書が単独で理解できる依頼文")
                                    }
                                }
                                putJsonArray("required") { add(JsonPrimitive("task")) }
                            }
                        }
                    }
                }
            }
        }
    }.toString()

    fun toolResponse(call: GeminiFunctionCall, response: JsonObject): String = buildJsonObject {
        putJsonObject("toolResponse") {
            putJsonArray("functionResponses") {
                addJsonObject {
                    put("id", call.id)
                    put("name", call.name)
                    put("response", response)
                }
            }
        }
    }.toString()

    fun activityStart(): String = realtimeInput { putJsonObject("activityStart") {} }

    fun activityEnd(): String = realtimeInput { putJsonObject("activityEnd") {} }

    fun audio(pcm: ByteArray, sampleRate: Int): String = realtimeInput {
        putJsonObject("audio") {
            put("data", Base64.encode(pcm))
            put("mimeType", "audio/pcm;rate=$sampleRate")
        }
    }

    fun historyText(history: List<GeminiTurn>): String {
        if (history.isEmpty()) return ""
        val lines = history.joinToString("\n") { "ユーザー: ${it.user}\nあなた: ${it.assistant}" }
        return "\n\nここまでの会話（古い順）。続きとして答えてください。\n$lines"
    }

    fun parse(message: String): GeminiServerEvent? {
        val root = json.parseToJsonElement(message).jsonObject
        root["error"]?.let { return GeminiServerEvent.Error(it.toString()) }
        if ("setupComplete" in root) return GeminiServerEvent.SetupComplete
        root["toolCall"]?.jsonObject?.let { toolCall ->
            return GeminiServerEvent.ToolCall(toolCall["functionCalls"]?.jsonArray.orEmpty().map { value ->
                val call = value.jsonObject
                GeminiFunctionCall(
                    id = call.getValue("id").jsonPrimitive.content,
                    name = call.getValue("name").jsonPrimitive.content,
                    args = call["args"]?.jsonObject ?: JsonObject(emptyMap()),
                )
            })
        }
        val content = root["serverContent"]?.jsonObject ?: return null
        var rate = OUTPUT_SAMPLE_RATE
        val audio = content["modelTurn"]?.jsonObject?.get("parts")?.jsonArray.orEmpty().mapNotNull { part ->
            val inline = part.jsonObject["inlineData"]?.jsonObject ?: return@mapNotNull null
            val mime = inline["mimeType"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (!mime.startsWith("audio/pcm")) return@mapNotNull null
            rate = pcmRate(mime) ?: rate
            inline["data"]?.jsonPrimitive?.contentOrNull?.let { Base64.decode(it) }
        }
        return GeminiServerEvent.Content(
            audio = audio,
            sampleRate = rate,
            inputText = content.text("inputTranscription"),
            outputText = content.text("outputTranscription"),
            turnComplete = content["turnComplete"]?.jsonPrimitive?.contentOrNull == "true",
        )
    }

    private fun JsonObject.text(key: String): String =
        this[key]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun pcmRate(mime: String): Int? =
        mime.split(";").drop(1).map { it.trim().split("=") }
            .firstOrNull { it.size == 2 && it[0] == "rate" }?.get(1)?.toIntOrNull()

    private fun realtimeInput(block: JsonObjectBuilder.() -> Unit): String =
        buildJsonObject { putJsonObject("realtimeInput", block) }.toString()
}
