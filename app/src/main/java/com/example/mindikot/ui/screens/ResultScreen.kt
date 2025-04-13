package com.example.mindikot.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.example.mindikot.core.engine.RoundEvaluator
// Corrected ViewModel and Factory imports
import com.example.mindikot.ui.viewmodel.GameViewModel
import com.example.mindikot.ui.viewmodel.factory.GameViewModelFactory

@Composable
fun ResultScreen(
    navController: NavHostController,
    // Use the factory to get the ViewModel instance
    viewModel: GameViewModel = viewModel(
        factory = GameViewModelFactory(LocalContext.current.applicationContext)
    )
) {
    // Observe the last known game state from the ViewModel
    val gameState by viewModel.state.collectAsState()

    // Calculate the round result based on the observed state
    // This recalculates if gameState changes, which shouldn't happen much on this screen
    // but ensures it uses the final state provided by the VM before navigation.
    val roundResult = remember(gameState) {
        // Ensure we have valid player/team data before evaluating
        if (gameState.players.isNotEmpty() && gameState.teams.isNotEmpty()) {
            RoundEvaluator.evaluateRound(gameState)
        } else {
            // Return a default/error result if state is invalid
            RoundEvaluator.RoundResult(null, false) // Default to Draw if state is bad
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Display Winner or Draw message
        Text(
            text = roundResult.winningTeam?.let { "Team ${it.id} Wins!" } ?: "Round is a Draw!",
            style = MaterialTheme.typography.headlineLarge
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Display Kot status or score info
        if (roundResult.winningTeam != null) {
            Text(
                text = if (roundResult.isKot) "KOT! All four 10s collected!" else "Regular Win",
                style = MaterialTheme.typography.bodyLarge
            )
            // TODO: Display actual scores if tracked in ViewModel/GameState
            // Text("Team ${roundResult.winningTeam.id} score: X")
        } else {
            Text(text = "Scores remain unchanged.", style = MaterialTheme.typography.bodyLarge)
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Button to go back to the Lobby
        Button(
            onClick = {
                // No need to explicitly stop server/client here.
                // LobbyScreen's DisposableEffect should handle cleanup when navigating back.
                 println("[UI - ResultScreen] Navigating back to lobby.")
                navController.navigate("lobby") {
                    // Pop everything back to the lobby start destination, clearing game/result screens
                    popUpTo("lobby") { inclusive = true }
                }
            }
        ) { Text("Back to Lobby") }

        // TODO: Add "Play Again" Button (Host Only)
        // if (viewModel.isHost) {
        //     Button(onClick = { /* Host triggers state reset & deal */ }) { Text("Play Again") }
        // }
    }
}