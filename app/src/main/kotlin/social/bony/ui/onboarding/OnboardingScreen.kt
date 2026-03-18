package social.bony.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun OnboardingScreen(
    onAccountAdded: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // Navigate away as soon as an account is successfully added
    LaunchedEffect(uiState.success) {
        if (uiState.success) onAccountAdded()
    }

    // Surface errors via snackbar
    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // Handle back on the nsecBunker form step
    BackHandler(enabled = uiState.showNsecBunkerForm) {
        viewModel.hideNsecBunkerForm()
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (uiState.showNsecBunkerForm) {
                NsecBunkerForm(
                    isLoading = uiState.isLoading,
                    onConnect = viewModel::addAccountWithNsecBunker,
                    onBack = viewModel::hideNsecBunkerForm,
                )
            } else {
                MethodPicker(
                    isLoading = uiState.isLoading,
                    onAmberClick = { viewModel.addAccountWithAmber(context.packageName) },
                    onNsecBunkerClick = viewModel::showNsecBunkerForm,
                    onLocalKeyClick = viewModel::addLocalKeyAccount,
                )
            }
        }
    }
}

@Composable
private fun MethodPicker(
    isLoading: Boolean,
    onAmberClick: () -> Unit,
    onNsecBunkerClick: () -> Unit,
    onLocalKeyClick: () -> Unit,
) {
    Text("🦴", style = MaterialTheme.typography.displayLarge)
    Spacer(Modifier.height(8.dp))
    Text("bony", style = MaterialTheme.typography.headlineLarge)
    Text(
        text = "A bare-bones Nostr client.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(48.dp))

    if (isLoading) {
        CircularProgressIndicator(modifier = Modifier.size(48.dp))
    } else {
        Button(
            onClick = onAmberClick,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Sign in with Amber")
        }

        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            onClick = onNsecBunkerClick,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Sign in with nsecBunker")
        }

        Spacer(Modifier.height(24.dp))

        Text(
            text = "No signer app? Generate a local key.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = onLocalKeyClick,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Create local key (advanced)")
        }
    }
}

@Composable
private fun NsecBunkerForm(
    isLoading: Boolean,
    onConnect: (String) -> Unit,
    onBack: () -> Unit,
) {
    var bunkerUrl by rememberSaveable { mutableStateOf("") }

    Text("nsecBunker", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        text = "Paste the bunker:// URI from your bunker app.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(32.dp))

    if (isLoading) {
        CircularProgressIndicator(modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Connecting to bunker…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        OutlinedTextField(
            value = bunkerUrl,
            onValueChange = { bunkerUrl = it },
            label = { Text("bunker:// URL") },
            placeholder = { Text("bunker://pubkey?relay=wss://…") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (bunkerUrl.isNotBlank()) onConnect(bunkerUrl) }),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = { onConnect(bunkerUrl) },
            enabled = bunkerUrl.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Connect")
        }

        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Back")
        }
    }
}
