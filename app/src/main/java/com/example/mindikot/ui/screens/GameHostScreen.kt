package com.example.mindikot.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color // Keep if used
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.mindikot.ui.GameViewModel // Ensure correct import path

@Composable
fun GameHostScreen(navController: NavController, viewModel: GameViewModel) {
    val gameState by viewModel.state.collectAsState()
    val connectedCount by viewModel.connectedPlayersCount.collectAsState()
    val requiredPlayerCount = viewModel.requiredPlayerCount
    val gameStarted by viewModel.gameStarted.collectAsState()

    // Navigate to game screen once the game starts
    LaunchedEffect(gameStarted) {
        if (gameStarted) {
            navController.navigate("game") {
                popUpTo("lobby") { inclusive = false }
            }
        }
    }

    // Server is now started via GameConfigCard -> initializeGameSettings -> startServerAndDiscovery
    // We don't need to start it here again.
    // LaunchedEffect(Unit) {
    //    viewModel.startServerAndDiscovery() // Correct function name
    // }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Game Host Lobby", style = MaterialTheme.typography.headlineMedium)
        Text("Mode: ${gameState.gameMode.name}")
        Text("Players: $requiredPlayerCount")
        Divider(modifier = Modifier.padding(vertical = 8.dp))

        Text("Waiting for players... ($connectedCount/$requiredPlayerCount)")

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(gameState.players, key = { it.id }) { player ->
                val isMe = player.id == viewModel.localPlayerId
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isMe) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
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

        Button(
            onClick = {
                viewModel.stopServerAndDiscovery() // Correct function name
                navController.navigate("lobby") {
                    popUpTo("lobby") { inclusive = true }
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) // Use error color for cancel
        ) {
            Text("Cancel Game")
        }

        Text(
            text = "Game will start automatically when all players join.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )
    }
}