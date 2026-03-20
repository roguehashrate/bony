package social.bony.ui.notifications

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import social.bony.ui.feed.NoteCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    onBack: () -> Unit,
    onThreadClick: (String) -> Unit,
    onProfileClick: (String) -> Unit,
    viewModel: NotificationsViewModel = hiltViewModel(),
) {
    val events by viewModel.events.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val reactions by viewModel.reactions.collectAsStateWithLifecycle()
    val activeAccount by viewModel.activeAccount.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.markAllRead() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notifications") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                isLoading && events.isEmpty() ->
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                events.isEmpty() ->
                    Text("No notifications yet.", modifier = Modifier.align(Alignment.Center))

                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(events, key = { it.id }) { event ->
                        NoteCard(
                            event = event,
                            profile = profiles[event.pubkey],
                            profiles = profiles,
                            onThreadClick = onThreadClick,
                            onProfileClick = onProfileClick,
                            reactors = reactions[event.id],
                            activePubkey = activeAccount?.pubkey,
                        )
                    }
                }
            }
        }
    }
}
