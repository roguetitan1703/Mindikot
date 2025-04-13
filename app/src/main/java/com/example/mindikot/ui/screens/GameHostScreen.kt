package com.example.mindikot.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
// Corrected ViewModel and Factory imports
import com.example.mindikot.ui.viewmodel.GameViewModel
import com.example.mindikot.ui.viewmodel.factory.GameViewModelFactory
import com.example.mindikot.ui.viewmodel.stopServerAndDiscovery // From GameViewModelHost.kt

import com.example.mindikot.ui.viewmodel.utils.log // Optional: Import log utility if needed here

@Composable
fun GameHostScreen(
    navController: NavController,
    // Use the factory to get the ViewModel instance
    viewModel: GameViewModel = viewModel(
        factory = GameViewModelFactory(LocalContext.current.applicationContext)
    )
) {
    val gameState by viewModel.state.collectAsState()
    // Use the dedicated count StateFlow from ViewModel
    val connectedCount by viewModel.connectedPlayersCount.collectAsState()
    // Get the definitive required count from ViewModel
    val requiredPlayerCount = viewModel.requiredPlayerCount // Access directly
    val gameStarted by viewModel.gameStarted.collectAsState()
    val hostIp by viewModel.hostIpAddress.collectAsState() // Get host IP for display

    // Log state changes for debugging UI
    // LaunchedEffect(connectedCount, gameState.players) {
    //     println("[UI - GameHostScreen] connectedCount = \$connectedCount, required = \$requiredPlayerCount")
    //     println("[UI - GameHostScreen] Player List State: \${gameState.players.map { p -> Pair(p.id, p.name) }}")
    // }


    // Navigate to game screen once the game starts
    LaunchedEffect(gameStarted) {
        if (gameStarted) {
             println("[UI - GameHostScreen] Game started state = true, navigating to game") // Log nav
            // Ensure navigation happens only once
            if (navController.currentDestination?.route != "game") {
                navController.navigate("game") {
                    // Pop up to lobby to clear hosting/waiting screens from backstack
                    popUpTo("lobby") { inclusive = false }
                }
            }
        }
    }

    // Server start is handled when navigating here from Lobby's GameConfigCard
    // Cleanup is handled by ViewModel's onCleared or explicitly calling stop

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Game Host Lobby", style = MaterialTheme.typography.headlineMedium)

        // Display config only if state is initialized
        if (requiredPlayerCount > 0 && gameState.players.isNotEmpty()) { // Check players list too
            Text("Mode: \${gameState.gameMode.name}")
            Text("Players Required: \$requiredPlayerCount")
            if(hostIp != null) {
                Text("Your IP: \$hostIp", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            } else {
                 Text("Advertising game...", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        } else {
            Text("Initializing host settings...")
        }
        Divider(modifier = Modifier.padding(vertical = 8.dp))

        // Use connectedCount and requiredPlayerCount correctly
        Text("Waiting for players... ($connectedCount/$requiredPlayerCount)")

        // Display players from the GameState
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f) // Takes remaining space
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Ensure players list is not empty before showing items
            if (gameState.players.isNotEmpty()) {
                items(gameState.players, key = { "player_${it.id}" }) { player -> // Use player ID for key
                    val isMe = player.id == viewModel.localPlayerId // Host is ID 0
                    val isConnected = player.name != "Waiting..." && player.name != "[Disconnected]" && !player.name.contains("[LEFT]")
                    val cardColor = when {
                         isMe -> MaterialTheme.colorScheme.primaryContainer
                         isConnected -> MaterialTheme.colorScheme.surfaceVariant
                         else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.6f) // Dim disconnected/waiting
                    }
                    val textColor = when {
                         isMe -> MaterialTheme.colorScheme.onPrimaryContainer
                         isConnected -> MaterialTheme.colorScheme.onSurfaceVariant
                         else -> LocalContentColor.current.copy(alpha = 0.6f)
                    }


                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = cardColor),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Text(
                            // Display player name and status
                            text = player.name + if (isMe) " (Host - You)" else "",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = textColor
                        )
                    }
                }
            } else {
                item { Text("Waiting for player data...")} // Placeholder if list is empty
            }
        }

        Button(
            onClick = {
                // Call the ViewModel function to stop hosting
                viewModel.stopServerAndDiscovery() // Use host extension method
                navController.navigate("lobby") {
                    popUpTo("lobby") { inclusive = true } // Go back to lobby, clear this screen
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) {
            Text("Cancel Game")
        }

        if (connectedCount < requiredPlayerCount) {
            Text(
                text = "Game will start automatically when all players join.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }
    }
}