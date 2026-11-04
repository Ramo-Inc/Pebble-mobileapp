package coredevices.ring.external.replyaudio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ReplyAudioDialog(onDismiss: () -> Unit) {
    val viewModel = koinViewModel<ReplyAudioViewModel>()
    val config by viewModel.config.collectAsState()
    val entries by viewModel.entries.collectAsState()
    val lastError by viewModel.lastError.collectAsState()
    var url by remember { mutableStateOf(config.url) }
    var token by remember { mutableStateOf(config.token) }
    var enabled by remember { mutableStateOf(config.enabled) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reply audio") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Server URL") },
                    placeholder = { Text("http://100.x.x.x:8765") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Token") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Auto-play replies", modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                lastError?.let {
                    Text("Error: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
                Text("Replies (${entries.size})", style = MaterialTheme.typography.titleSmall)
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(entries, key = { it.id }) { entry ->
                        ReplyRow(entry, onPlay = { viewModel.replay(entry.id) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                viewModel.save(ReplyAudioConfig(url = url, token = token, enabled = enabled))
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun ReplyRow(entry: ReplyLogEntry, onPlay: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = (if (entry.played) "" else "● ") + entry.createdAt.shortLabel(),
                style = MaterialTheme.typography.labelSmall,
            )
            Text(entry.text.ifBlank { "(no text)" }, style = MaterialTheme.typography.bodyMedium)
        }
        if (entry.hasAudio) {
            TextButton(onClick = onPlay) { Text("Play") }
        }
    }
}

private fun Instant.shortLabel(): String {
    val t = toLocalDateTime(TimeZone.currentSystemDefault())
    fun Int.pad() = toString().padStart(2, '0')
    return "${t.monthNumber}/${t.dayOfMonth} ${t.hour.pad()}:${t.minute.pad()}"
}
