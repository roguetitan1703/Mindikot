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
import com.example.mindikot.core.model.GameMode
import com.example.mindikot.ui.GameViewModel

@Composable
fun GameHostScreen(navController: NavController, viewModel: GameViewModel) {
    val gameState by viewModel.state.collectAsState()
    val requiredPlayerCount = if (gameState.players.size == 4) 4 else 6
    val players = gameState.players
    val gameMode = gameState.gameMode

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Game Host Lobby", style = MaterialTheme.typography.headlineMedium)

        Text("Game Mode: ${gameMode.name}")
        Text("Waiting for players... (${players.size}/$requiredPlayerCount)")

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

        Button(
            onClick = { navController.navigate("game") },
            enabled = players.size == requiredPlayerCount
        ) {
            Text("Start Game")
        }
    }
}
