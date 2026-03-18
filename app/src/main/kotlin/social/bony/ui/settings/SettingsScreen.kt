package social.bony.ui.settings

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onAddAccount: () -> Unit,
    onAccountManagement: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            ListItem(
                headlineContent = { Text("Account management") },
                supportingContent = { Text("Add or remove accounts") },
                leadingContent = { Icon(Icons.Default.AccountCircle, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onAccountManagement),
            )

            HorizontalDivider()

            ListItem(
                headlineContent = { Text("Share logs") },
                supportingContent = { Text("Send debug logs to help report issues") },
                trailingContent = {
                    IconButton(onClick = {
                        val intent = Intent.createChooser(
                            viewModel.logRepository.buildShareIntent(),
                            "Share logs via",
                        )
                        context.startActivity(intent)
                    }) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Share logs")
                    }
                },
            )
        }
    }
}
