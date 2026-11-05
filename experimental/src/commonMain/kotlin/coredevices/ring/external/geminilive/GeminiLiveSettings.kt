package coredevices.ring.external.geminilive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coredevices.ring.ui.theme.IndexTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

class GeminiLiveSettingsViewModel(
    private val keyStorage: GeminiLiveKeyStorage,
    private val history: GeminiConversationHistory,
) : ViewModel() {
    private val _hasKey = MutableStateFlow(false)
    val hasKey: StateFlow<Boolean> = _hasKey.asStateFlow()
    val turns: StateFlow<List<GeminiTurn>> = history.turns

    fun refresh() {
        viewModelScope.launch { _hasKey.value = keyStorage.get() != null }
    }

    fun saveKey(apiKey: String) {
        viewModelScope.launch {
            keyStorage.save(apiKey)
            _hasKey.value = apiKey.isNotBlank()
        }
    }

    fun resetConversation() {
        viewModelScope.launch { history.clear() }
    }
}

/** Shown under the Gemini Live choice in a gesture's destination sheet. */
@Composable
fun GeminiLiveSettingsSection() {
    val viewModel = koinViewModel<GeminiLiveSettingsViewModel>()
    val hasKey by viewModel.hasKey.collectAsState()
    val turns by viewModel.turns.collectAsState()
    var draft by remember { mutableStateOf("") }
    val colors = IndexTheme.colors
    LaunchedEffect(Unit) { viewModel.refresh() }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            singleLine = true,
            label = { Text("Gemini API key") },
            placeholder = { Text(if (hasKey) "Saved" else "Not set") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Remembers ${turns.size} of the last ${GeminiConversationHistory.MAX_TURNS} exchanges",
                color = colors.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = viewModel::resetConversation, enabled = turns.isNotEmpty()) {
                Text("Reset conversation")
            }
            TextButton(
                onClick = {
                    viewModel.saveKey(draft)
                    draft = ""
                },
                enabled = draft.isNotBlank(),
            ) { Text("Save key") }
        }
    }
}
