package coredevices.ring.external.replyaudio

import coredevices.api.ApiClient
import coredevices.ring.api.ApiConfig
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.isSuccess
import kotlin.time.Duration.Companion.seconds

interface ReplyAudioApi {
    suspend fun fetchReplies(baseUrl: String, token: String, after: Long?): ReplyListResponse
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
}
