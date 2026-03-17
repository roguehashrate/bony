package social.bony.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import social.bony.account.AccountRepository
import social.bony.ui.feed.FeedScreen
import social.bony.ui.onboarding.OnboardingScreen
import social.bony.ui.thread.ThreadScreen
import javax.inject.Inject

private const val ROUTE_ONBOARDING = "onboarding"
private const val ROUTE_FEED = "feed"
private const val ROUTE_THREAD = "thread/{eventId}"

@Composable
fun BonyNavHost() {
    val viewModel: StartupViewModel = hiltViewModel()
    val startupState by viewModel.startupState.collectAsStateWithLifecycle()
    val navController = rememberNavController()

    when (startupState) {
        StartupState.Loading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        is StartupState.Ready -> {
            val start = if ((startupState as StartupState.Ready).hasAccount)
                ROUTE_FEED else ROUTE_ONBOARDING

            NavHost(navController = navController, startDestination = start) {
                composable(ROUTE_ONBOARDING) {
                    OnboardingScreen(
                        onAccountAdded = {
                            navController.navigate(ROUTE_FEED) {
                                popUpTo(ROUTE_ONBOARDING) { inclusive = true }
                            }
                        }
                    )
                }
                composable(ROUTE_FEED) {
                    FeedScreen(
                        onThreadClick = { eventId ->
                            navController.navigate("thread/$eventId")
                        }
                    )
                }
                composable(ROUTE_THREAD) {
                    ThreadScreen(onBack = { navController.popBackStack() })
                }
            }
        }
    }
}

// ── Startup ViewModel ─────────────────────────────────────────────────────────

sealed interface StartupState {
    data object Loading : StartupState
    data class Ready(val hasAccount: Boolean) : StartupState
}

@HiltViewModel
class StartupViewModel @Inject constructor(
    accountRepository: AccountRepository,
) : ViewModel() {
    val startupState: StateFlow<StartupState> = accountRepository.activeAccount
        .map { account -> StartupState.Ready(hasAccount = account != null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StartupState.Loading)
}
