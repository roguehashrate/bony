package social.bony.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import social.bony.nostr.Event
import social.bony.nostr.Nip19
import social.bony.nostr.ProfileContent
import social.bony.nostr.isReply
import social.bony.nostr.replyEventId
import social.bony.nostr.replyToPubkeys
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun NoteCard(
    event: Event,
    profile: ProfileContent?,
    profiles: Map<String, ProfileContent> = emptyMap(),
    highlighted: Boolean = false,
    onThreadClick: ((eventId: String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val background = if (highlighted) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
    Column(modifier = modifier.background(background).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(pictureUrl = profile?.picture, modifier = Modifier.size(40.dp))

            Spacer(Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = profile?.bestName ?: event.pubkey.abbreviateAsNpub(),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (profile?.bestName != null)
                            MaterialTheme.colorScheme.onSurface
                        else
                            MaterialTheme.colorScheme.primary,
                        fontFamily = if (profile?.bestName == null) FontFamily.Monospace else null,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = remember(event.createdAt) { event.createdAt.formatRelative() },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (event.parsedTags.isReply) {
            val replyTargets = event.parsedTags.replyToPubkeys
            val replyLabel = replyTargets.take(1).joinToString { pubkey ->
                profiles[pubkey]?.bestName?.truncate(20)
                    ?: pubkey.abbreviateAsNpub()
            } + if (replyTargets.size > 1) " +${replyTargets.size - 1}" else ""
            val parentId = event.parsedTags.replyEventId
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(start = 50.dp)
                    .then(
                        if (onThreadClick != null && parentId != null)
                            Modifier.clickable { onThreadClick(parentId) }
                        else Modifier
                    ),
            ) {
                Text(
                    text = "↩ $replyLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = event.content,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            overflow = TextOverflow.Ellipsis,
            maxLines = 12,
            modifier = Modifier.padding(start = 50.dp),
        )
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun Avatar(pictureUrl: String?, modifier: Modifier = Modifier) {
    if (pictureUrl != null) {
        AsyncImage(
            model = pictureUrl,
            contentDescription = "Avatar",
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(CircleShape),
        )
    } else {
        Icon(
            imageVector = Icons.Default.AccountCircle,
            contentDescription = "Avatar",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
    }
}

// ── Formatting helpers ────────────────────────────────────────────────────────

private fun String.truncate(max: Int): String =
    if (length <= max) this else "${take(max)}…"

private fun String.abbreviateAsNpub(): String {
    val npub = Nip19.hexToNpub(this)
    return "${npub.take(12)}…${npub.takeLast(6)}"
}

private fun Long.formatRelative(): String {
    val now = System.currentTimeMillis() / 1000
    val delta = now - this
    return when {
        delta < 60      -> "now"
        delta < 3600    -> "${delta / 60}m"
        delta < 86400   -> "${delta / 3600}h"
        delta < 604800  -> "${delta / 86400}d"
        else -> Instant.ofEpochSecond(this)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MMM d"))
    }
}
