package social.bony.ui.thread

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import social.bony.nostr.Event
import social.bony.nostr.EventKind
import social.bony.nostr.ProfileContent
import social.bony.nostr.quotedEventId
import social.bony.ui.feed.NoteCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(
    onBack: () -> Unit,
    onProfileClick: (pubkey: String) -> Unit = {},
    onThreadClick: (eventId: String) -> Unit = {},
    viewModel: ThreadViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val quotedEvents by viewModel.quotedEvents.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // Build display list: [root?, gap?, parent?, focused, replies...]
    val items = buildList {
        uiState.root?.let { add(ThreadItem.Note(it)) }
        if (uiState.showGap) add(ThreadItem.Gap)
        uiState.parent?.let { add(ThreadItem.Note(it)) }
        uiState.focused?.let { add(ThreadItem.Note(it, focused = true)) }
        uiState.replies.forEach { add(ThreadItem.Note(it)) }
    }

    // Scroll to the focused note once it appears
    LaunchedEffect(items.size, uiState.focusedEventId) {
        val index = items.indexOfFirst {
            it is ThreadItem.Note && it.event.id == uiState.focusedEventId
        }
        if (index >= 0) listState.animateScrollToItem(index)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Thread") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { padding ->
        when {
            uiState.isLoading && items.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }

            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(padding),
                ) {
                    itemsIndexed(items, key = { _, item -> item.key }) { _, item ->
                        when (item) {
                            is ThreadItem.Note -> NoteCard(
                                event = item.event,
                                profile = profiles[item.event.pubkey],
                                profiles = profiles,
                                highlighted = item.focused,
                                quotedEvent = run {
                                    val refId = when (item.event.kind) {
                                        EventKind.REPOST ->
                                            item.event.parsedTags.firstOrNull { it.name == "e" }?.value()
                                        else -> item.event.parsedTags.quotedEventId
                                    }
                                    refId?.let { quotedEvents[it] }
                                },
                                onThreadClick = onThreadClick,
                                onProfileClick = onProfileClick,
                            )
                            ThreadItem.Gap -> GapIndicator()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GapIndicator() {
    Text(
        text = "· · ·",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 6.dp, horizontal = 72.dp),
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

private sealed interface ThreadItem {
    val key: Any

    data class Note(val event: Event, val focused: Boolean = false) : ThreadItem {
        override val key get() = event.id
    }

    data object Gap : ThreadItem {
        override val key get() = "gap"
    }
}
