package com.example.mindikot.ui.navigation // Keep or adjust package as needed

import android.content.Context // Import Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext // Import LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
// Import your screens correctly
import com.example.mindikot.ui.screens.*
// **** FIX: Import ViewModel and Factory from new location ****
import com.example.mindikot.ui.viewmodel.GameViewModel
import com.example.mindikot.ui.viewmodel.factory.GameViewModelFactory


@Composable
fun MainNavHost(navController: NavHostController, modifier: Modifier = Modifier) {
    // Get Application Context once for the factory
    val applicationContext = LocalContext.current.applicationContext
    // Use the factory from the correct location
    val factory = GameViewModelFactory(applicationContext) // Create factory instance
    val gameViewModel: GameViewModel = viewModel(factory = factory)
    NavHost(navController = navController, startDestination = "lobby", modifier = modifier) {
        composable("lobby") {
            // Create or get ViewModel scoped to this destination using the factory

            // Pass the specific instance to the screen
            LobbyScreen(navController = navController, viewModel = gameViewModel)
        }
        composable("game_host") { // Screen where host waits

            GameHostScreen(navController = navController, viewModel = gameViewModel)
        }
        composable("waiting_for_players") { // Screen where joiner waits

            WaitingForPlayersScreen(navController = navController, viewModel = gameViewModel)
        }
        composable("game") { // The actual game screen

            GameScreen(navController = navController, viewModel = gameViewModel)
        }
        composable("result") {

            ResultScreen(navController = navController, viewModel = gameViewModel)
        }
    }
}