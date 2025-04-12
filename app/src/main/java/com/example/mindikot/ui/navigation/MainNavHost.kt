package com.example.mindikot.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mindikot.GameViewModel
import com.example.mindikot.ui.screens.GameScreen
import com.example.mindikot.ui.screens.LobbyScreen
import com.example.mindikot.ui.screens.ResultScreen

@Composable
fun MainNavHost(navController: NavHostController, modifier: Modifier = Modifier) {
    NavHost(navController = navController, startDestination = "lobby", modifier = modifier) {
        composable("lobby") {
            LobbyScreen(navController = navController)
        }
        composable("game") {
            // Use viewModel() to get an instance of GameViewModel
            val gameViewModel: GameViewModel = viewModel()
            GameScreen(navController = navController, viewModel = gameViewModel)
        }
        composable("result") {
            ResultScreen(navController = navController)
        }
    }
}
