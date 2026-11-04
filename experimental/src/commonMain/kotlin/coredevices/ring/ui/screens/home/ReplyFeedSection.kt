package coredevices.ring.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coredevices.ring.external.replyaudio.ReplyAudioRules
import coredevices.ring.external.replyaudio.ReplyLogEntry
import coredevices.ring.ui.theme.IndexTheme
import coredevices.util.relativeTime

internal fun LazyListScope.repliesSection(
    entries: List<ReplyLogEntry>,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onPlay: (ReplyLogEntry) -> Unit,
) {
    item("reply-header") {
        FeedSectionHeader(left = "Replies", right = entries.size.toString(), onClick = null, topPad = 14.dp)
    }
    items(items = ReplyAudioRules.feedPreview(entries, expanded), key = { "reply-${it.id}" }) { entry ->
        ReplyCard(entry, onPlay = { onPlay(entry) })
    }
    if (entries.size > ReplyAudioRules.FEED_PREVIEW_COUNT) {
        item("reply-more") {
            Text(
                if (expanded) "Show less" else "Show more",
                color = IndexTheme.colors.primary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clickable(role = Role.Button) { onToggleExpanded() }
                    .padding(horizontal = 22.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ReplyCard(entry: ReplyLogEntry, onPlay: () -> Unit) {
    val colors = IndexTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surfaceContainerLow)
            .border(1.dp, colors.outlineVariant, RoundedCornerShape(14.dp))
            .let { if (entry.hasAudio) it.clickable(role = Role.Button, onClickLabel = "Play reply") { onPlay() } else it }
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!entry.played) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(colors.primary))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                relativeTime(entry.createdAt).uppercase(),
                color = colors.onSurfaceVariant,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            entry.text.ifBlank { "(no text)" },
            color = colors.onSurface,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
