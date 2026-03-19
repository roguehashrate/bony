package social.bony.ui.feed

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import social.bony.nostr.Nip19
import social.bony.nostr.quotedEventId
import social.bony.ui.components.AccountSwitcher
import social.bony.ui.feed.extractInlineQuoteId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    onThreadClick: (eventId: String) -> Unit = {},
    onComposeClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onProfileClick: (pubkey: String) -> Unit = {},
    onRelayManagementClick: () -> Unit = {},
    onReplyClick: (social.bony.nostr.Event) -> Unit = {},
    onQuoteClick: (social.bony.nostr.Event) -> Unit = {},
    viewModel: FeedViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val currentFeed by viewModel.currentFeed.collectAsStateWithLifecycle()
    val activeAccount by viewModel.activeAccount.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val relayStatuses by viewModel.relayStatuses.collectAsStateWithLifecycle()
    val quotedEvents by viewModel.quotedEvents.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val onShare = remember {
        { event: social.bony.nostr.Event ->
            val noteUri = "nostr:${Nip19.hexToNote(event.id)}"
            val shareText = buildString {
                val text = event.content.take(280).trim()
                if (text.isNotEmpty()) { append(text); append("\n\n") }
                append(noteUri)
            }
            context.startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, shareText) },
                "Share note",
            ))
        }
    }

    val listState = rememberLazyListState()
    val pullToRefreshState = rememberPullToRefreshState()

    // Scroll to top whenever the feed buffer is flushed (account switch or refresh)
    LaunchedEffect(Unit) {
        viewModel.scrollToTop.collect { listState.scrollToItem(0) }
    }

    if (pullToRefreshState.isRefreshing) {
        LaunchedEffect(Unit) { viewModel.refresh() }
    }
    LaunchedEffect(uiState.isLoading) {
        if (!uiState.isLoading) pullToRefreshState.endRefresh()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    AccountSwitcher(
                        activeAccount = activeAccount,
                        accounts = accounts,
                        onSwitch = viewModel::switchAccount,
                        onProfileClick = activeAccount?.let { { onProfileClick(it.pubkey) } },
                    )
                },
                actions = {
                    val overallStatus = when {
                        relayStatuses.values.any { it == social.bony.nostr.relay.RelayStatus.CONNECTED }  -> social.bony.nostr.relay.RelayStatus.CONNECTED
                        relayStatuses.values.any { it == social.bony.nostr.relay.RelayStatus.CONNECTING } -> social.bony.nostr.relay.RelayStatus.CONNECTING
                        else -> social.bony.nostr.relay.RelayStatus.DISCONNECTED
                    }
                    IconButton(onClick = onRelayManagementClick) {
                        social.bony.ui.settings.RelayStatusDot(overallStatus)
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onComposeClick) {
                Icon(Icons.Default.Edit, contentDescription = "New note")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            TabRow(selectedTabIndex = currentFeed.ordinal) {
                Tab(
                    selected = currentFeed == FeedTab.HOME,
                    onClick = { viewModel.switchFeed(FeedTab.HOME) },
                    text = { Text("Home") },
                )
                Tab(
                    selected = currentFeed == FeedTab.GLOBAL,
                    onClick = { viewModel.switchFeed(FeedTab.GLOBAL) },
                    text = { Text("Global") },
                )
            }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(pullToRefreshState.nestedScrollConnection),
        ) {
            when {
                uiState.isLoading && uiState.events.isEmpty() -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                uiState.events.isEmpty() -> {
                    Text("No notes yet.", modifier = Modifier.align(Alignment.Center))
                }

                else -> {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(uiState.events, key = { it.id }) { event ->
                            NoteCard(
                                event = event,
                                profile = profiles[event.pubkey],
                                profiles = profiles,
                                quotedEvent = run {
                                    val refId = when (event.kind) {
                                        social.bony.nostr.EventKind.REPOST ->
                                            event.parsedTags.firstOrNull { it.name == "e" }?.value()
                                        else -> event.parsedTags.quotedEventId
                                            ?: extractInlineQuoteId(event.content)
                                    }
                                    refId?.let { quotedEvents[it] }
                                },
                                onThreadClick = onThreadClick,
                                onProfileClick = onProfileClick,
                                onReply = onReplyClick,
                                onBoost = viewModel::boost,
                                onQuote = onQuoteClick,
                                onShare = onShare,
                            )
                        }
                    }
                }
            }

            PullToRefreshContainer(
                state = pullToRefreshState,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
        }
    }
}
