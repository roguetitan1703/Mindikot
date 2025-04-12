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
    val gameViewModel: GameViewModel = viewModel() // ViewModel created once here

    NavHost(navController = navController, startDestination = "lobby", modifier = modifier) {
        composable("lobby") {
            LobbyScreen(navController = navController, viewModel = gameViewModel)
        }
        composable("game") {
            GameScreen(navController = navController, viewModel = gameViewModel)
        }
        composable("result") {
            ResultScreen(navController = navController) // no VM needed here
        }
        composable("waiting_for_players") {
            WaitingForPlayersScreen(navController = navController, viewModel = gameViewModel)
        }
        composable("game_host") {
            GameHostScreen(navController = navController, viewModel = gameViewModel)
        }
    }
}
