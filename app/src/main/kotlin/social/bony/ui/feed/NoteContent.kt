package social.bony.ui.feed

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
private val VIDEO_EXTENSIONS = setOf("mp4", "webm", "mov", "m4v", "mkv")
private val URL_REGEX = Regex("""https?://\S+""")

sealed class MediaItem {
    data class Image(val url: String) : MediaItem()
    data class Video(val url: String) : MediaItem()
}

data class ParsedContent(
    val text: String,
    val mediaItems: List<MediaItem>,
)

fun parseNoteContent(content: String): ParsedContent {
    val mediaItems = mutableListOf<MediaItem>()
    val text = URL_REGEX.replace(content) { match ->
        val url = match.value
        val ext = url.substringAfterLast('.').lowercase()
            .substringBefore('?').substringBefore('#')
        when {
            ext in IMAGE_EXTENSIONS -> { mediaItems.add(MediaItem.Image(url)); "" }
            ext in VIDEO_EXTENSIONS -> { mediaItems.add(MediaItem.Video(url)); "" }
            else -> match.value
        }
    }.replace(Regex(" {2,}"), " ").replace(Regex("\n{3,}"), "\n\n").trim()

    return ParsedContent(text, mediaItems)
}

@Composable
fun NoteMediaContent(mediaItems: List<MediaItem>, modifier: Modifier = Modifier) {
    if (mediaItems.isEmpty()) return
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        mediaItems.forEach { item ->
            when (item) {
                is MediaItem.Image -> InlineImage(item.url)
                is MediaItem.Video -> InlineVideo(item.url)
            }
        }
    }
}

@Composable
private fun InlineImage(url: String) {
    var expanded by remember { mutableStateOf(false) }
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = if (expanded) ContentScale.FillWidth else ContentScale.Crop,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (!expanded) Modifier.height(220.dp) else Modifier)
            .clip(RoundedCornerShape(8.dp))
            .clickable { expanded = !expanded },
    )
}

@Composable
private fun InlineVideo(url: String) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1C1C1E))
            .clickable {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { type = "video/*" }
                context.startActivity(intent)
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.PlayCircle,
            contentDescription = "Play video",
            tint = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.size(56.dp),
        )
        Text(
            text = Uri.parse(url).host ?: url,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.5f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(8.dp),
        )
    }
}
