package com.example.mindikot.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.mindikot.ui.GameViewModel

@Composable
fun GameHostScreen(navController: NavController, viewModel: GameViewModel) {
    val gameState by viewModel.state.collectAsState()
    val requiredPlayerCount = if (gameState.players.size == 4) 4 else 6
    val players = gameState.players
    val gameMode = gameState.gameMode
    var isGameLoading by remember { mutableStateOf(true) } // Track if the game is being created

    // Simulating the game creation process, replace with actual logic (e.g., network call)
    LaunchedEffect(isGameLoading) {
        // Fake loading delay
        if (isGameLoading) {
            // Simulate waiting for the game to be ready (e.g., create host)
            kotlinx.coroutines.delay(2000) // This simulates a 2-second loading delay for creating the game
            isGameLoading = false
        }
    }
    LaunchedEffect(Unit) {
        viewModel.startServer()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Game Host Lobby", style = MaterialTheme.typography.headlineMedium)

        // Show loading indicator while creating the game
        if (isGameLoading) {
            CircularProgressIndicator()
            Text("Creating game...", style = MaterialTheme.typography.bodyMedium)
        } else {
            // Game creation finished, now show the host lobby screen
            Text("Game Mode: ${gameMode.name}")
            Text("Waiting for players... (${players.size}/$requiredPlayerCount)")

            // List of players joining the game
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(players) { player ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                    ) {
                        Text(
                            text = player.name,
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }

            // Button to start the game when the required player count is met
            Button(
                onClick = { navController.navigate("game") },
                enabled = players.size == requiredPlayerCount
            ) {
                Text("Start Game")
            }
            // Back button at the top
            Button(
                onClick = {
                    viewModel.stopServer()
                    navController.navigate("lobby") {
                        popUpTo("lobby") { inclusive = true }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Text("Go Back to Lobby")
            }

        }
    }
}
