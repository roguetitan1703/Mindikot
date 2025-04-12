package com.example.mindikot.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.mindikot.ui.screens.GameScreen
import com.example.mindikot.ui.screens.LobbyScreen
import com.example.mindikot.ui.screens.ResultScreen

@Composable
fun MainNavHost(navController: NavHostController) {
    NavHost(navController, startDestination = "lobby") {
        composable("lobby") { LobbyScreen(navController) }
        composable("game") { GameScreen(navController) }
        composable("result") { ResultScreen(navController) }
    }
}
