package com.example.mindikot.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.mindikot.ui.GameViewModel // Ensure correct import
import kotlinx.coroutines.flow.collectLatest

@Composable
fun WaitingForPlayersScreen(
        navController: NavController,
        viewModel: GameViewModel =
                viewModel(
                        factory =
                                GameViewModelFactory(
                                        LocalContext.current.applicationContext
                                ) // Use factory
                )
) {
    val gameState by viewModel.state.collectAsState()
    val connectedCount by viewModel.connectedPlayersCount.collectAsState()
    val isHost = viewModel.isHost
    val requiredPlayers = viewModel.requiredPlayerCount
    val gameStarted by viewModel.gameStarted.collectAsState()
    val localPlayerId = viewModel.localPlayerId

    // Joiner: Navigate when game starts
    LaunchedEffect(gameStarted, isHost) {
        if (!isHost && gameStarted) {
            navController.navigate("game") { popUpTo("waiting_for_players") { inclusive = true } }
        }
    }

    // Joiner: Handle disconnection while waiting
    LaunchedEffect(Unit) {
        viewModel.state.collectLatest { state ->
            // If client and no longer in player list (after initial assignment)
            if (!isHost && localPlayerId != -1 && state.players.none { it.id == localPlayerId }) {
                // Might have been kicked or host stopped
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
        CircularProgressIndicator()
        Text("Connected Players: $connectedCount / $requiredPlayers")
        Spacer(modifier = Modifier.height(16.dp))

        Text("Players in Lobby:", style = MaterialTheme.typography.titleMedium)
        Column(horizontalAlignment = Alignment.Start, modifier = Modifier.padding(start = 16.dp)) {
            // Use players list from GameState
            gameState.players.forEach { player ->
                val suffix =
                        when {
                            player.id == localPlayerId -> " (You)"
                            player.id == 0 && isHost -> " (Host - You)" // Clarify host
                            player.id == 0 && !isHost -> " (Host)"
                            else -> ""
                        }
                Text(text = "- ${player.name}$suffix", style = MaterialTheme.typography.bodyLarge)
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        if (isHost) {
            // Host just waits, game starts automatically
            Text("Waiting for players to join...")
            Button(
                    onClick = {
                        viewModel.stopServerAndDiscovery() // Host cancels the game
                        navController.navigate("lobby") { popUpTo("lobby") { inclusive = true } }
                    },
                    colors =
                            ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                            )
            ) { Text("Cancel Game") }
        } else {
            // Client waits for host
            Text("Waiting for the host to start the game...")
            Button(
                    onClick = {
                        viewModel.disconnectFromServer() // Client leaves
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
