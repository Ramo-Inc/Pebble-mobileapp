package coredevices.ring.external.replyaudio

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow

class ReplyAudioViewModel(
    private val preferences: ReplyAudioPreferences,
    private val log: ReplyAudioLog,
    private val service: ReplyAudioService,
) : ViewModel() {
    val config: StateFlow<ReplyAudioConfig> = preferences.config
    val entries: StateFlow<List<ReplyLogEntry>> = log.entries
    val lastError: StateFlow<String?> = service.lastError

    fun save(config: ReplyAudioConfig) = preferences.save(config)

    fun replay(id: Long) = service.replay(id)
}
