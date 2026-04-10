package com.felix.occlient.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.felix.occlient.OcClientApplication
import com.felix.occlient.ui.screens.ChatScreen
import com.felix.occlient.ui.screens.HomeScreen
import com.felix.occlient.ui.screens.LogScreen
import com.felix.occlient.ui.screens.SettingsScreen
import com.felix.occlient.viewmodel.ChatViewModel
import com.felix.occlient.viewmodel.HomeViewModel
import com.felix.occlient.viewmodel.SettingsViewModel

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Settings : Screen("settings")
    object Chat : Screen("chat/{sessionId}") {
        fun createRoute(sessionId: String) = "chat/$sessionId"
    }
    object Log : Screen("log")
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val app = context.applicationContext as OcClientApplication

    NavHost(navController = navController, startDestination = Screen.Home.route) {
        composable(Screen.Home.route) {
            val viewModel = HomeViewModel(app.sessionRepository)
            HomeScreen(
                viewModel = viewModel,
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                onNavigateToChat = { sessionId ->
                    navController.navigate(Screen.Chat.createRoute(sessionId))
                }
            )
        }
        composable(Screen.Settings.route) {
            val viewModel = SettingsViewModel(app.settingsDataStore)
            SettingsScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(
            route = Screen.Chat.route,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType })
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
            val viewModel: ChatViewModel = viewModel(
                factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        ChatViewModel(sessionId, app.sessionRepository, app.settingsDataStore) as T
                }
            )
            ChatScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToLog = { navController.navigate(Screen.Log.route) }
            )
        }
        composable(Screen.Log.route) {
            LogScreen(onNavigateBack = { navController.popBackStack() })
        }
    }
}
