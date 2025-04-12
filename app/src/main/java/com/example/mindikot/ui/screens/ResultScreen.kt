package com.example.mindikot.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.example.mindikot.core.engine.RoundEvaluator
import com.example.mindikot.ui.GameViewModel

@Composable
fun ResultScreen(
    navController: NavHostController,
    viewModel: GameViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val gameState by viewModel.state.collectAsState()

    val roundResult = remember(gameState.teams) {
        RoundEvaluator.evaluateRound(gameState.teams)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "🏆 Team ${roundResult.winningTeam.id} wins!",
            style = MaterialTheme.typography.headlineLarge
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = if (roundResult.isKot) "💥 KOT! All four 10s collected!" else "Regular Win",
            style = MaterialTheme.typography.bodyLarge
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(onClick = {
            viewModel.restartGame()
            navController.navigate("lobby") {
                popUpTo("result") { inclusive = true }
            }
        }) {
            Text("Back to Lobby")
        }
    }
}
