package com.example.mindikot.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.mindikot.ui.GameViewModel

@Composable
fun WaitingForPlayersScreen(navController: NavController, viewModel: GameViewModel = viewModel()) {
    val gameState by viewModel.state.collectAsState()
    val isHost = viewModel.isHost
    val players = gameState.players
    val requiredPlayers = viewModel.requiredPlayerCount
    val gameStarted by viewModel.gameStarted.collectAsState()

    // Auto-navigate for joiners when game starts
    LaunchedEffect(gameStarted) {
        if (!isHost && gameStarted) {
            navController.navigate("game") {
                popUpTo("waiting_for_players") { inclusive = true }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Waiting for Players...", style = MaterialTheme.typography.headlineMedium)

        Text("Connected Players: ${players.size} / $requiredPlayers")

        // Show the player names
        players.forEach {
            Text(text = it.name, style = MaterialTheme.typography.bodyLarge)
        }

        if (isHost) {
            Text("You are the host. Start the game when ready.")
            Button(
                onClick = {
                    viewModel.startGame()
                    navController.navigate("game")
                },
                enabled = players.size == requiredPlayers
            ) {
                Text("Start Game")
            }
        } else {
            Text("Waiting for host to start the game...")
        }
    }
}
