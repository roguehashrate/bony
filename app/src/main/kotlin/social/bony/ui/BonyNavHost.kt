package social.bony.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import social.bony.account.AccountRepository
import social.bony.notifications.DeepLinkHandler
import social.bony.ui.compose.ComposeScreen
import social.bony.ui.notifications.NotificationsScreen
import social.bony.ui.feed.FeedScreen
import social.bony.ui.hashtag.HashtagFeedScreen
import social.bony.ui.onboarding.OnboardingScreen
import social.bony.ui.profile.ProfileScreen
import social.bony.ui.search.SearchScreen
import social.bony.ui.settings.AccountManagementScreen
import social.bony.ui.settings.RelayManagementScreen
import social.bony.ui.settings.SettingsScreen
import social.bony.ui.thread.ThreadScreen
import javax.inject.Inject

private const val ROUTE_ONBOARDING = "onboarding"
private const val ROUTE_FEED = "feed"
private const val ROUTE_THREAD = "thread/{eventId}"
private const val ROUTE_COMPOSE = "compose?replyToId={replyToId}&quoteToId={quoteToId}"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_PROFILE = "profile/{pubkey}"
private const val ROUTE_ADD_ACCOUNT = "add_account"
private const val ROUTE_ACCOUNT_MANAGEMENT = "account_management"
private const val ROUTE_RELAY_MANAGEMENT = "relay_management"
private const val ROUTE_NOTIFICATIONS = "notifications"
private const val ROUTE_SEARCH = "search"
private const val ROUTE_HASHTAG = "hashtag/{tag}"

@Composable
fun BonyNavHost() {
    val viewModel: StartupViewModel = hiltViewModel()
    val startupState by viewModel.startupState.collectAsStateWithLifecycle()
    val navController = rememberNavController()

    // Handle nostr: deep links and notification taps.
    LaunchedEffect(Unit) {
        viewModel.openThread.collect { eventId -> navController.navigate("thread/$eventId") }
    }
    LaunchedEffect(Unit) {
        viewModel.openProfile.collect { pubkey -> navController.navigate("profile/$pubkey") }
    }

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
                        onThreadClick = { eventId -> navController.navigate("thread/$eventId") },
                        onComposeClick = { navController.navigate("compose") },
                        onSettingsClick = { navController.navigate(ROUTE_SETTINGS) },
                        onProfileClick = { pubkey -> navController.navigate("profile/$pubkey") },
                        onRelayManagementClick = { navController.navigate(ROUTE_RELAY_MANAGEMENT) },
                        onSearchClick = { navController.navigate(ROUTE_SEARCH) },
                        onNotificationsClick = { navController.navigate(ROUTE_NOTIFICATIONS) },
                        onReplyClick = { event -> navController.navigate("compose?replyToId=${event.id}") },
                        onQuoteClick = { event -> navController.navigate("compose?quoteToId=${event.id}") },
                    )
                }
                composable(ROUTE_NOTIFICATIONS) {
                    NotificationsScreen(
                        onBack = { navController.popBackStack() },
                        onThreadClick = { eventId -> navController.navigate("thread/$eventId") },
                        onProfileClick = { pubkey -> navController.navigate("profile/$pubkey") },
                    )
                }
                composable(ROUTE_THREAD) {
                    ThreadScreen(
                        onBack = { navController.popBackStack() },
                        onProfileClick = { pubkey -> navController.navigate("profile/$pubkey") },
                        onThreadClick = { eventId -> navController.navigate("thread/$eventId") },
                        onReplyClick = { event -> navController.navigate("compose?replyToId=${event.id}") },
                        onQuoteClick = { event -> navController.navigate("compose?quoteToId=${event.id}") },
                    )
                }
                composable(
                    ROUTE_COMPOSE,
                    arguments = listOf(
                        navArgument("replyToId") { type = NavType.StringType; defaultValue = "" },
                        navArgument("quoteToId") { type = NavType.StringType; defaultValue = "" },
                    ),
                ) {
                    ComposeScreen(onBack = { navController.popBackStack() })
                }
                composable(ROUTE_SETTINGS) {
                    SettingsScreen(
                        onBack = { navController.popBackStack() },
                        onAddAccount = { navController.navigate(ROUTE_ADD_ACCOUNT) },
                        onAccountManagement = { navController.navigate(ROUTE_ACCOUNT_MANAGEMENT) },
                        onRelayManagement = { navController.navigate(ROUTE_RELAY_MANAGEMENT) },
                    )
                }
                composable(ROUTE_RELAY_MANAGEMENT) {
                    RelayManagementScreen(onBack = { navController.popBackStack() })
                }
                composable(ROUTE_ACCOUNT_MANAGEMENT) {
                    AccountManagementScreen(
                        onBack = { navController.popBackStack() },
                        onAddAccount = { navController.navigate(ROUTE_ADD_ACCOUNT) },
                    )
                }
                composable(ROUTE_ADD_ACCOUNT) {
                    OnboardingScreen(
                        onAccountAdded = {
                            navController.navigate(ROUTE_FEED) {
                                popUpTo(ROUTE_FEED) { inclusive = false }
                            }
                        }
                    )
                }
                composable(ROUTE_PROFILE) {
                    ProfileScreen(
                        onBack = { navController.popBackStack() },
                        onThreadClick = { eventId -> navController.navigate("thread/$eventId") },
                        onProfileClick = { pubkey -> navController.navigate("profile/$pubkey") },
                    )
                }
                composable(ROUTE_SEARCH) {
                    SearchScreen(
                        onBack = { navController.popBackStack() },
                        onProfileClick = { pubkey -> navController.navigate("profile/$pubkey") },
                        onHashtagClick = { tag -> navController.navigate("hashtag/$tag") },
                    )
                }
                composable(ROUTE_HASHTAG) {
                    HashtagFeedScreen(
                        onBack = { navController.popBackStack() },
                        onThreadClick = { eventId -> navController.navigate("thread/$eventId") },
                        onProfileClick = { pubkey -> navController.navigate("profile/$pubkey") },
                    )
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
    deepLinkHandler: DeepLinkHandler,
) : ViewModel() {
    val startupState: StateFlow<StartupState> = accountRepository.activeAccount
        .map { account -> StartupState.Ready(hasAccount = account != null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StartupState.Loading)

    val openThread: SharedFlow<String> = deepLinkHandler.openThread
    val openProfile: SharedFlow<String> = deepLinkHandler.openProfile
}
