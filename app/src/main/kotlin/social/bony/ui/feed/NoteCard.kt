package social.bony.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Repeat
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
import social.bony.nostr.EventKind
import social.bony.nostr.Nip19
import social.bony.nostr.ProfileContent
import social.bony.nostr.isReply
import social.bony.nostr.quotedEventId
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
    quotedEvent: Event? = null,
    onThreadClick: ((eventId: String) -> Unit)? = null,
    onProfileClick: ((pubkey: String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val background = if (highlighted) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface

    // ── Kind-6 repost: "X boosted" header + inner card ───────────────────────
    if (event.kind == EventKind.REPOST) {
        Column(modifier = modifier.background(background)) {
            // Boost header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 4.dp)
                    .then(
                        if (onProfileClick != null)
                            Modifier.clickable { onProfileClick(event.pubkey) }
                        else Modifier
                    ),
            ) {
                Icon(
                    imageVector = Icons.Default.Repeat,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "${profile?.bestName ?: event.pubkey.abbreviateAsNpub()} boosted",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (quotedEvent != null) {
                QuotedNoteCard(
                    event = quotedEvent,
                    profile = profiles[quotedEvent.pubkey],
                    profiles = profiles,
                    onThreadClick = onThreadClick,
                    onProfileClick = onProfileClick,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
                Spacer(Modifier.height(8.dp))
            } else {
                Text(
                    text = "Loading boosted note…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        return
    }

    // ── Kind-1 text note (possibly with inline quote) ─────────────────────────
    Column(modifier = modifier.background(background).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = if (onProfileClick != null)
                Modifier.clickable { onProfileClick(event.pubkey) }
            else Modifier,
        ) {
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

        val hasQuote = event.parsedTags.quotedEventId != null
            || extractInlineQuoteId(event.content) != null
        val parsed = remember(event.content) { parseNoteContent(event.content) }

        if (parsed.text.isNotEmpty()) {
            Text(
                text = parsed.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                overflow = TextOverflow.Ellipsis,
                maxLines = 12,
                modifier = Modifier.padding(start = 50.dp),
            )
        }

        if (parsed.mediaItems.isNotEmpty()) {
            Spacer(Modifier.height(if (parsed.text.isNotEmpty()) 8.dp else 0.dp))
            NoteMediaContent(mediaItems = parsed.mediaItems, modifier = Modifier.fillMaxWidth())
        }

        // Inline quote card (kind-1 with q tag)
        if (hasQuote) {
            Spacer(Modifier.height(8.dp))
            if (quotedEvent != null) {
                QuotedNoteCard(
                    event = quotedEvent,
                    profile = profiles[quotedEvent.pubkey],
                    profiles = profiles,
                    onThreadClick = onThreadClick,
                    onProfileClick = onProfileClick,
                    modifier = Modifier.padding(start = 50.dp),
                )
            } else {
                Text(
                    text = "Loading quoted note…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 50.dp),
                )
            }
        }
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** A compact nested card used for reposts and quote-notes. No further quote nesting. */
@Composable
fun QuotedNoteCard(
    event: Event,
    profile: ProfileContent?,
    profiles: Map<String, ProfileContent> = emptyMap(),
    onThreadClick: ((eventId: String) -> Unit)? = null,
    onProfileClick: ((pubkey: String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(
                if (onThreadClick != null)
                    Modifier.clickable { onThreadClick(event.id) }
                else Modifier
            )
            .padding(10.dp),
    ) {
        // Author row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = if (onProfileClick != null)
                Modifier.clickable { onProfileClick(event.pubkey) }
            else Modifier,
        ) {
            Avatar(pictureUrl = profile?.picture, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = profile?.bestName ?: event.pubkey.abbreviateAsNpub(),
                style = MaterialTheme.typography.labelMedium,
                color = if (profile?.bestName != null)
                    MaterialTheme.colorScheme.onSurface
                else
                    MaterialTheme.colorScheme.primary,
                fontFamily = if (profile?.bestName == null) FontFamily.Monospace else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = remember(event.createdAt) { event.createdAt.formatRelative() },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val parsed = remember(event.content) { parseNoteContent(event.content) }

        if (parsed.text.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = parsed.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (parsed.mediaItems.isNotEmpty()) {
            Spacer(Modifier.height(if (parsed.text.isNotEmpty()) 6.dp else 0.dp))
            NoteMediaContent(mediaItems = parsed.mediaItems, modifier = Modifier.fillMaxWidth())
        }
    }
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
