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
import com.example.mindikot.ui.GameViewModel // Ensure correct import

@Composable
fun ResultScreen(
    navController: NavHostController,
    viewModel: GameViewModel = viewModel(
        factory = GameViewModelFactory(LocalContext.current.applicationContext) // Use factory if needed
    )
) {
    val gameState by viewModel.state.collectAsState()

    // Calculate result based on last known state
    val roundResult = remember(gameState) {
        if (gameState.players.isNotEmpty()) {
            RoundEvaluator.evaluateRound(gameState)
        } else {
            RoundEvaluator.RoundResult(null, false)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = roundResult.winningTeam?.let { "Team ${it.id} Wins!" } ?: "Round is a Draw!",
            style = MaterialTheme.typography.headlineLarge
        )
        Spacer(modifier = Modifier.height(16.dp))

        if (roundResult.winningTeam != null) {
            Text(
                text = if (roundResult.isKot) "KOT! All four 10s collected!" else "Regular Win",
                style = MaterialTheme.typography.bodyLarge
            )
        } else {
            Text(text = "Scores remain unchanged.", style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = {
                // No need to stop server/disconnect here. That happens when LobbyScreen recomposes.
                // Just navigate back.
                navController.navigate("lobby") {
                    popUpTo("lobby") { inclusive = true } // Pop everything back to lobby start
                }
            }
        ) { Text("Back to Lobby") }

        // TODO: Add "Play Again" logic if desired (Host only)
        // This would involve the host triggering a state reset and dealing again.
    }
}