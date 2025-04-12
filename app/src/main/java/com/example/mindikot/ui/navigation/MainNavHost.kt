package com.example.mindikot.ui

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
// Import the factory
import com.example.mindikot.ui.GameViewModelFactory


@Composable
fun MainNavHost(navController: NavHostController, modifier: Modifier = Modifier) {
    // Get Application Context once
    val applicationContext = LocalContext.current.applicationContext

    NavHost(navController = navController, startDestination = "lobby", modifier = modifier) {
        composable("lobby") {
            // Provide the factory here
            val gameViewModel: GameViewModel = viewModel(factory = GameViewModelFactory(applicationContext))
            LobbyScreen(navController = navController, viewModel = gameViewModel)
        }
        composable("game_host") { // Screen where host waits
            // Provide the factory here
            val gameViewModel: GameViewModel = viewModel(factory = GameViewModelFactory(applicationContext))
            GameHostScreen(navController = navController, viewModel = gameViewModel)
        }
        composable("waiting_for_players") { // Screen where joiner waits
            // Provide the factory here
            val gameViewModel: GameViewModel = viewModel(factory = GameViewModelFactory(applicationContext))
            WaitingForPlayersScreen(navController = navController, viewModel = gameViewModel)
        }
        composable("game") { // The actual game screen
            // Provide the factory here
            val gameViewModel: GameViewModel = viewModel(factory = GameViewModelFactory(applicationContext))
            GameScreen(navController = navController, viewModel = gameViewModel)
        }
        composable("result") {
            // Provide the factory here
            val gameViewModel: GameViewModel = viewModel(factory = GameViewModelFactory(applicationContext))
            ResultScreen(navController = navController, viewModel = gameViewModel)
        }
    }
}