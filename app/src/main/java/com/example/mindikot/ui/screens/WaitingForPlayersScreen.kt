package com.example.mindikot.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.widget.Toast
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
// Corrected ViewModel and Factory imports
import com.example.mindikot.ui.viewmodel.GameViewModel
import com.example.mindikot.ui.viewmodel.factory.GameViewModelFactory
import kotlinx.coroutines.flow.collectLatest
import com.example.mindikot.ui.viewmodel.disconnectFromServer // From GameViewModelClient.kt


@Composable
fun WaitingForPlayersScreen(
    navController: NavController,
    // Use the factory to get the ViewModel instance
    viewModel: GameViewModel = viewModel(
        factory = GameViewModelFactory(LocalContext.current.applicationContext)
    )
) {
    val gameState by viewModel.state.collectAsState()
    // Use the dedicated count StateFlow
    val connectedCount by viewModel.connectedPlayersCount.collectAsState()
    val isHost = viewModel.isHost // Should be false for this screen (Joiner uses this)
    // Get the definitive required count (might come from initial GameState update)
    val requiredPlayers = viewModel.requiredPlayerCount // Relies on host having set this correctly
    val gameStarted by viewModel.gameStarted.collectAsState()
    val localPlayerId = viewModel.localPlayerId // Access directly

    val context = LocalContext.current

    // Log state changes for debugging UI
    // LaunchedEffect(connectedCount, gameState.players, localPlayerId, requiredPlayers) {
    //     println("[UI - WaitingScreen] connectedCount = $connectedCount, required = $requiredPlayers")
    //     println("[UI - WaitingScreen] Player List State: ${gameState.players.map { p -> Pair(p.id, p.name) }}")
    //     println("[UI - WaitingScreen] Local Player ID = $localPlayerId")
    // }

    // Joiner: Navigate to game screen when game starts
    LaunchedEffect(gameStarted, isHost) {
        // Ensure this is a client and the game has started
        if (!isHost && gameStarted) {
             println("[UI - WaitingScreen] Game started signal received. Navigating to game screen.") // Log nav
             // Ensure navigation happens only once
             if (navController.currentDestination?.route != "game") {
                  navController.navigate("game") {
                      // Pop this waiting screen off the back stack
                      popUpTo("waiting_for_players") { inclusive = true }
                  }
             }
        }
    }

    // Joiner: Monitor connection status and navigate back if disconnected
    LaunchedEffect(viewModel.isConnectedToServer) {
        // If the connection state becomes false *after* being on this screen
        if (!viewModel.isConnectedToServer && navController.currentDestination?.route == "waiting_for_players") {
             println("[UI - WaitingScreen] isConnectedToServer became false. Navigating to lobby.") // Log nav
             Toast.makeText(context, "Disconnected from host.", Toast.LENGTH_LONG).show()
             // No need to call disconnectFromServer here, it should have been called by the listener ending
             navController.navigate("lobby") {
                 popUpTo("lobby") { inclusive = true } // Go back to start
             }
        }
    }
     // Also listen for specific errors that indicate disconnection
     LaunchedEffect(viewModel.showError) {
         viewModel.showError.collectLatest { errorMsg ->
             if (errorMsg.contains("Kicked", ignoreCase = true) || errorMsg.contains("Disconnected", ignoreCase = true)) {
                  if (navController.currentDestination?.route == "waiting_for_players") {
                       navController.navigate("lobby") {
                            popUpTo("lobby") { inclusive = true }
                       }
                  }
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
        CircularProgressIndicator()

        // Use connectedCount and potentially requiredPlayers from GameState if available
        val displayRequired = gameState.players.size.takeIf { it > 0 } ?: requiredPlayers
        if (displayRequired > 0) { // Display only if requiredPlayers is initialized or derived
            Text("Connected Players: $connectedCount / $displayRequired")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text("Players in Lobby:", style = MaterialTheme.typography.titleMedium)
        // Display players from the received gameState
        Column(
             modifier = Modifier.padding(start = 16.dp).fillMaxWidth(), // Allow text to wrap
             horizontalAlignment = Alignment.CenterHorizontally, // Center text lines
             verticalArrangement = Arrangement.spacedBy(4.dp)
         ) {
            if (gameState.players.isNotEmpty()) {
                gameState.players.forEach { player ->
                    // Determine player status/role for display
                    val suffix = when {
                        player.id == localPlayerId -> " (You)"
                        player.id == 0 -> " (Host)" // Client sees player 0 as Host
                        else -> ""
                    }
                     val playerDisplayName = player.name.takeIf { it != "Waiting..." } ?: "Connecting..."
                     val displayColor = if (player.name == "[Disconnected]" || player.name.contains("[LEFT]")) Color.Gray else LocalContentColor.current

                    Text(
                        text = "- $playerDisplayName$suffix",
                        style = MaterialTheme.typography.bodyLarge,
                        color = displayColor
                    )
                }
            } else {
                Text("Waiting for lobby details...")
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Client only has Leave button here
        if (!isHost) {
             Text("Waiting for the host to start the game...")
             Button(
                 onClick = {
                     // Call ViewModel's disconnect function using client extension
                     viewModel.disconnectFromServer()
                     // Navigate back to lobby
                     navController.navigate("lobby") {
                         popUpTo("lobby") { inclusive = true } // Go back to start, clear stack
                     }
                 },
                 colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
             ) {
                 Text("Leave Lobby")
             }
        }
    }
}