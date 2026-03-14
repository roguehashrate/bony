package social.bony.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

@Composable
fun BonyNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "feed") {
        composable("feed") { FeedScreen() }
    }
}
