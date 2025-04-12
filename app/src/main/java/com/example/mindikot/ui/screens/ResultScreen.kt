package com.example.mindikot.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.example.mindikot.core.engine.RoundEvaluator
import com.example.mindikot.ui.GameViewModel // Ensure correct import

@Composable
fun ResultScreen(
        navController: NavHostController,
        viewModel: GameViewModel = viewModel() // Use default viewModel()
) {
    // Observe the state to get the final round details if needed,
    // but the result should ideally be passed via navigation arguments
    // or a separate ViewModel event after being calculated by the host.
    // For simplicity now, recalculate based on last known state.
    val gameState by viewModel.state.collectAsState()

    // Calculate result - In a real app, this result should be passed from the GameScreen/ViewModel
    // or retrieved from a specific "round end" state update.
    // Recalculating here might show stale data if state reset too quickly.
    val roundResult =
            remember(gameState) {
                // Ensure there are players before evaluating
                if (gameState.players.isNotEmpty()) {
                    RoundEvaluator.evaluateRound(gameState)
                } else {
                    // Default or error state if gameState is empty/invalid
                    RoundEvaluator.RoundResult(null, false)
                }
            }

    Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
                // Handle potential draw (null winningTeam)
                text = roundResult.winningTeam?.let { "Team ${it.id} Wins!" } ?: "Round is a Draw!",
                style = MaterialTheme.typography.headlineLarge
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Only show Kot message if there was a winner
        if (roundResult.winningTeam != null) {
            Text(
                    text = if (roundResult.isKot) "KOT! All four 10s collected!" else "Regular Win",
                    style = MaterialTheme.typography.bodyLarge
            )
        } else {
            Text(text = "Scores remain unchanged.", style = MaterialTheme.typography.bodyLarge)
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Button to go back to Lobby
        Button(
                onClick = {
                    // Stop network connections before going back
                    if (viewModel.isHost) {
                        viewModel.stopServer()
                    } else {
                        viewModel.disconnectFromServer()
                    }
                    // Navigate back to lobby, clearing the game stack
                    navController.navigate("lobby") { popUpTo("lobby") { inclusive = true } }
                }
        ) { Text("Back to Lobby") }

        // TODO: Add "Play Again" button for the host
        // if (viewModel.isHost) {
        //    Button(onClick = { /* viewModel.startNextRound() */ }) { Text("Play Next Round") }
        // }
    }
}
