package com.example.mindikot.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.mindikot.ui.GameViewModel // Ensure correct import
import kotlinx.coroutines.flow.collectLatest

@Composable
fun WaitingForPlayersScreen(navController: NavController, viewModel: GameViewModel = viewModel()) {
    val gameState by viewModel.state.collectAsState()
    val connectedCount by viewModel.connectedPlayersCount.collectAsState()
    val isHost = viewModel.isHost // This should be set correctly when joining/hosting
    val requiredPlayers = viewModel.requiredPlayerCount
    val gameStarted by viewModel.gameStarted.collectAsState()
    val localPlayerId = viewModel.localPlayerId

    // Effect to handle navigation for JOINER when game starts
    LaunchedEffect(gameStarted, isHost) {
        if (!isHost && gameStarted) {
            // Game started message received from host
            navController.navigate("game") {
                // Remove waiting screen from back stack
                popUpTo("waiting_for_players") { inclusive = true }
            }
        }
    }

    // Effect to handle disconnection while waiting
    LaunchedEffect(Unit) {
        // Assuming ViewModel state indicates connection status implicitly
        // If ViewModel had an explicit isConnected StateFlow, observe that.
        // For now, if we lose state or go back to empty, navigate back.
        viewModel.state.collectLatest { state ->
            if (!isHost &&
                            state.players.find { it.id == localPlayerId } == null &&
                            localPlayerId != -1
            ) {
                // If I'm a client, my ID is assigned, but I'm not in the player list anymore,
                // likely means I got disconnected or host reset.
                // Navigate back to lobby.
                navController.navigate("lobby") { popUpTo("lobby") { inclusive = true } }
            }
        }
    }

    Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Waiting for Players...", style = MaterialTheme.typography.headlineMedium)

        CircularProgressIndicator() // Show progress while waiting

        Text("Connected Players: $connectedCount / $requiredPlayers")

        Spacer(modifier = Modifier.height(16.dp))

        Text("Players in Lobby:", style = MaterialTheme.typography.titleMedium)
        // Show the player names from the GameState received from host
        Column(horizontalAlignment = Alignment.Start) {
            gameState.players.forEach { player ->
                val suffix =
                        if (player.id == localPlayerId) " (You)"
                        else if (player.id == 0) " (Host)" else ""
                Text(text = "- ${player.name}$suffix", style = MaterialTheme.typography.bodyLarge)
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        if (isHost) {
            // Host doesn't manually start, it's automatic
            Text("Waiting for players to join...")
            // Optionally add a cancel button for the host
            Button(
                    onClick = {
                        viewModel.stopServer()
                        navController.navigate("lobby") { popUpTo("lobby") { inclusive = true } }
                    },
                    colors =
                            ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                            )
            ) { Text("Cancel Game") }
        } else {
            Text("Waiting for the host to start the game...")
            // Optionally add a leave button for the client
            Button(
                    onClick = {
                        viewModel.disconnectFromServer()
                        navController.navigate("lobby") { popUpTo("lobby") { inclusive = true } }
                    },
                    colors =
                            ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondary
                            )
            ) { Text("Leave Lobby") }
        }
    }
}
