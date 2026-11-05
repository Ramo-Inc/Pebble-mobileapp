package coredevices.ring.external.geminilive

import co.touchlab.kermit.Logger
import com.russhwolf.settings.Settings
import coredevices.util.integrations.IntegrationTokenStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** The latest exchanges with Gemini, oldest first, passed to every new session as context. */
class GeminiConversationHistory(private val settings: Settings) {
    companion object {
        const val MAX_TURNS = 20
        private const val KEY = "gemini_live_history"
        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = ListSerializer(GeminiTurn.serializer())
        private val logger = Logger.withTag("GeminiConversationHistory")
    }

    private val mutex = Mutex()
    private val _turns = MutableStateFlow(load())
    val turns: StateFlow<List<GeminiTurn>> = _turns.asStateFlow()

    suspend fun append(turn: GeminiTurn) = mutex.withLock {
        persist((_turns.value + turn).takeLast(MAX_TURNS))
    }

    suspend fun clear() = mutex.withLock { persist(emptyList()) }

    private fun persist(turns: List<GeminiTurn>) {
        settings.putString(KEY, json.encodeToString(serializer, turns))
        _turns.value = turns
    }

    private fun load(): List<GeminiTurn> {
        val raw = settings.getStringOrNull(KEY) ?: return emptyList()
        return try {
            json.decodeFromString(serializer, raw).takeLast(MAX_TURNS)
        } catch (e: Exception) {
            logger.w(e) { "Discarding unreadable conversation history" }
            emptyList()
        }
    }
}

class GeminiLiveKeyStorage(private val tokenStorage: IntegrationTokenStorage) {
    private companion object {
        const val KEY = "gemini_live_api_key"
    }

    suspend fun get(): String? = tokenStorage.getToken(KEY)?.takeIf { it.isNotBlank() }

    suspend fun save(apiKey: String) {
        if (apiKey.isBlank()) tokenStorage.deleteToken(KEY) else tokenStorage.saveToken(KEY, apiKey.trim())
    }
}
