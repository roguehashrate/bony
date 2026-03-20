package social.bony.ui.profile

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import social.bony.nostr.Nip19
import social.bony.ui.feed.NoteCard

private val BANNER_HEIGHT = 160.dp
private val AVATAR_SIZE = 72.dp
private val AVATAR_OFFSET = AVATAR_SIZE / 2

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    onThreadClick: (eventId: String) -> Unit = {},
    onProfileClick: (pubkey: String) -> Unit = {},
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isActiveUserProfile by viewModel.isActiveUserProfile.collectAsStateWithLifecycle()
    val isFollowing by viewModel.isFollowing.collectAsStateWithLifecycle()
    val isFollowLoading by viewModel.isFollowLoading.collectAsStateWithLifecycle()
    val reactions by viewModel.reactions.collectAsStateWithLifecycle()
    val activePubkey by viewModel.activePubkey.collectAsStateWithLifecycle()
    val pubkey = viewModel.pubkey

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val onShare = remember(pubkey, profile) {
        {
            val npub = Nip19.hexToNpub(pubkey)
            val shareText = buildString {
                profile?.bestName?.let { append("$it\n") }
                append("nostr:$npub")
            }
            context.startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, shareText)
                },
                "Share profile",
            ))
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(profile?.bestName ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onShare) {
                        Icon(Icons.Default.Share, contentDescription = "Share profile")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item {
                // ── Banner + Avatar ────────────────────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(BANNER_HEIGHT + AVATAR_OFFSET),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(BANNER_HEIGHT)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        if (profile?.banner != null) {
                            AsyncImage(
                                model = profile?.banner,
                                contentDescription = "Banner",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }

                    val avatarMod = Modifier
                        .size(AVATAR_SIZE)
                        .align(Alignment.BottomStart)
                        .offset(x = 16.dp)
                        .clip(CircleShape)
                    if (profile?.picture != null) {
                        AsyncImage(
                            model = profile?.picture,
                            contentDescription = "Avatar",
                            contentScale = ContentScale.Crop,
                            modifier = avatarMod,
                        )
                    } else {
                        Box(modifier = avatarMod.background(MaterialTheme.colorScheme.primary))
                    }
                }

                // ── Profile info ───────────────────────────────────────────
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Spacer(Modifier.height(8.dp))

                    val displayName = profile?.bestName
                    if (displayName != null) {
                        Text(displayName, style = MaterialTheme.typography.titleLarge)
                    }

                    val npub = Nip19.hexToNpub(pubkey)
                    Text(
                        text = "${npub.take(12)}…${npub.takeLast(8)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.clickable {
                            clipboardManager.setText(AnnotatedString(npub))
                            scope.launch { snackbarHostState.showSnackbar("Copied npub") }
                        },
                    )

                    if (profile?.nip05 != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "✓ ${profile?.nip05}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    if (!profile?.about.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        SelectionContainer {
                            Text(
                                text = profile?.about ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }

                    // ── Follow / Unfollow button ───────────────────────────
                    if (!isActiveUserProfile) {
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isFollowLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            if (isFollowing) {
                                OutlinedButton(
                                    onClick = viewModel::unfollow,
                                    enabled = !isFollowLoading,
                                ) {
                                    Text("Unfollow")
                                }
                            } else {
                                Button(
                                    onClick = viewModel::follow,
                                    enabled = !isFollowLoading,
                                ) {
                                    Text("Follow")
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                }
            }

            if (isLoading && notes.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            items(notes, key = { it.id }) { event ->
                NoteCard(
                    event = event,
                    profile = profile,
                    onThreadClick = onThreadClick,
                    onProfileClick = onProfileClick,
                    onLike = viewModel::react,
                    reactors = reactions[event.id],
                    activePubkey = activePubkey,
                )
            }
        }
    }
}
