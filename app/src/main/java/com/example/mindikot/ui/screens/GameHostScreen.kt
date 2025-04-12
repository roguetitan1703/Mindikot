package com.example.mindikot.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.mindikot.ui.GameViewModel // Ensure correct import path

@Composable
fun GameHostScreen(navController: NavController, viewModel: GameViewModel) {
    val gameState by viewModel.state.collectAsState()
    val connectedCount by viewModel.connectedPlayersCount.collectAsState()
    val requiredPlayerCount = viewModel.requiredPlayerCount
    val gameStarted by viewModel.gameStarted.collectAsState()

    // Navigate to game screen once the game starts (cards are dealt)
    LaunchedEffect(gameStarted) {
        if (gameStarted) {
            navController.navigate("game") {
                // Optional: Pop up to prevent going back to host lobby once game starts
                popUpTo("lobby") { inclusive = false }
            }
        }
    }

    // Start server when entering this screen (if not already running)
    // Using key=Unit ensures it runs only once when the composable enters composition
    LaunchedEffect(Unit) {
        viewModel.startServer() // Start server when host enters lobby
    }

    Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Game Host Lobby", style = MaterialTheme.typography.headlineMedium)

        // Show game configuration details
        Text("Mode: ${gameState.gameMode.name}")
        Text("Players: $requiredPlayerCount")
        Divider(modifier = Modifier.padding(vertical = 8.dp))

        Text("Waiting for players... ($connectedCount/$requiredPlayerCount)")

        // Loading indicator could be shown if needed, but server starts immediately
        // if (viewModel.isServerStarting) { // Need a state in ViewModel for this
        //    CircularProgressIndicator()
        // }

        // List of players (use names from gameState)
        LazyColumn(
                modifier =
                        Modifier.fillMaxWidth()
                                .weight(1f) // Take available space
                                .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(gameState.players, key = { it.id }) { player ->
                val isMe = player.id == viewModel.localPlayerId // Host is ID 0
                Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors =
                                CardDefaults.cardColors(
                                        containerColor =
                                                if (isMe) MaterialTheme.colorScheme.primaryContainer
                                                else
                                                        MaterialTheme.colorScheme
                                                                .surfaceVariant // Differentiate
                                        // host card
                                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Text(
                            text = player.name + if (isMe) " (Host)" else "",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }

        // Button to go back (stops the server)
        Button(
                onClick = {
                    viewModel.stopServer() // Ensure server is stopped
                    navController.navigate("lobby") {
                        popUpTo("lobby") { inclusive = true } // Go back to lobby selection
                    }
                },
                colors =
                        ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary
                        )
        ) { Text("Cancel Game") }

        Text(
                text = "Game will start automatically when all players join.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
        )
    }
}
