package com.example.mindikot.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mindikot.ui.GameViewModel
import com.example.mindikot.ui.screens.*

@Composable
fun MainNavHost(navController: NavHostController, modifier: Modifier = Modifier) {
    NavHost(navController = navController, startDestination = "lobby", modifier = modifier) {
        composable("lobby") {
            LobbyScreen(navController = navController)
        }
        composable("game") {
            val gameViewModel: GameViewModel = viewModel()
            GameScreen(navController = navController, viewModel = gameViewModel)
        }
        composable("result") {
            ResultScreen(navController = navController)
        }
        composable("waiting_for_players") {
            val gameViewModel: GameViewModel = viewModel()
            WaitingForPlayersScreen(navController = navController, viewModel = gameViewModel)
        }
        composable("game_host") {
            val gameViewModel: GameViewModel = viewModel()
            GameHostScreen(navController = navController, viewModel = gameViewModel)
        }
    }
}
