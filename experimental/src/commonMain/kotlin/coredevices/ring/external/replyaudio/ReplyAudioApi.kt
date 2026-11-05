package coredevices.ring.external.replyaudio

import coredevices.api.ApiClient
import coredevices.ring.api.ApiConfig
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlin.time.Duration.Companion.seconds

interface ReplyAudioApi {
    suspend fun fetchReplies(baseUrl: String, token: String, after: Long?): ReplyListResponse

    suspend fun fetchReply(baseUrl: String, token: String, id: Long): ReplyDto

    /** Reads the PCM stream at [path] and hands each chunk to [onChunk] as it arrives. */
    suspend fun streamAudio(baseUrl: String, token: String, path: String, onChunk: suspend (ByteArray) -> Unit)
}

class ReplyAudioHttpException(val status: Int) : Exception("Reply server returned HTTP $status")

class ReplyAudioApiImpl(config: ApiConfig) : ReplyAudioApi, ApiClient(config.version, timeout = 30.seconds) {
    override suspend fun fetchReplies(baseUrl: String, token: String, after: Long?): ReplyListResponse {
        val response = client.get("${baseUrl.trimEnd('/')}/replies") {
            if (after != null) parameter("after", after)
            if (token.isNotBlank()) bearerAuth(token)
        }
        if (!response.status.isSuccess()) throw ReplyAudioHttpException(response.status.value)
        return response.body()
    }

    override suspend fun fetchReply(baseUrl: String, token: String, id: Long): ReplyDto {
        val response = client.get("${baseUrl.trimEnd('/')}/replies/$id") {
            if (token.isNotBlank()) bearerAuth(token)
        }
        if (!response.status.isSuccess()) throw ReplyAudioHttpException(response.status.value)
        return response.body()
    }

    override suspend fun streamAudio(baseUrl: String, token: String, path: String, onChunk: suspend (ByteArray) -> Unit) {
        client.prepareGet("${baseUrl.trimEnd('/')}/${path.trimStart('/')}") {
            if (token.isNotBlank()) bearerAuth(token)
        }.execute { response ->
            if (!response.status.isSuccess()) throw ReplyAudioHttpException(response.status.value)
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(STREAM_READ_BYTES)
            while (true) {
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read < 0) break
                if (read > 0) onChunk(buffer.copyOf(read))
            }
        }
    }

    private companion object {
        const val STREAM_READ_BYTES = 8192
    }
}
