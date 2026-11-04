package coredevices.ring.external.replyaudio

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ReplyAudioConfig(
    val url: String = "",
    val token: String = "",
    val enabled: Boolean = false,
) {
    val isUsable: Boolean get() = enabled && url.isNotBlank()
}

class ReplyAudioPreferences(private val settings: Settings) {

    companion object {
        private const val URL_KEY = "reply_audio_url"
        private const val TOKEN_KEY = "reply_audio_token"
        private const val ENABLED_KEY = "reply_audio_enabled"
        private const val CURSOR_KEY = "reply_audio_cursor"
    }

    private val _config = MutableStateFlow(
        ReplyAudioConfig(
            url = settings.getString(URL_KEY, ""),
            token = settings.getString(TOKEN_KEY, ""),
            enabled = settings.getBoolean(ENABLED_KEY, false),
        )
    )
    val config: StateFlow<ReplyAudioConfig> = _config.asStateFlow()

    fun save(config: ReplyAudioConfig) {
        val previousUrl = _config.value.url
        settings.putString(URL_KEY, config.url.trim())
        settings.putString(TOKEN_KEY, config.token.trim())
        settings.putBoolean(ENABLED_KEY, config.enabled)
        // A different server numbers its replies independently.
        if (config.url.trim() != previousUrl) settings.remove(CURSOR_KEY)
        _config.value = config.copy(url = config.url.trim(), token = config.token.trim())
    }

    /** Id of the last reply fetched from the server; null before the first fetch. */
    var cursor: Long?
        get() = if (settings.hasKey(CURSOR_KEY)) settings.getLong(CURSOR_KEY, 0) else null
        set(value) {
            if (value == null) settings.remove(CURSOR_KEY) else settings.putLong(CURSOR_KEY, value)
        }
}
