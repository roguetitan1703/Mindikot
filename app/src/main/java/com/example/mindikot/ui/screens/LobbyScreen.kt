package com.example.mindikot.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

@Composable
fun LobbyScreen(navController: NavController) {
    var playerName by remember { mutableStateOf("") }
    var gameMode by remember { mutableStateOf("CHOOSE_WHEN_EMPTY") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Mindikot Lobby", style = MaterialTheme.typography.headlineMedium)

        OutlinedTextField(
            value = playerName,
            onValueChange = { playerName = it },
            label = { Text("Enter your name") }
        )

        Text("Select Game Mode:")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { gameMode = "CHOOSE_WHEN_EMPTY" }) {
                Text("Choose When Empty")
            }
            Button(onClick = { gameMode = "FIRST_CARD_HIDDEN" }) {
                Text("First Card Hidden")
            }
        }

        Button(
            onClick = { navController.navigate("game") },
            enabled = playerName.isNotBlank()
        ) {
            Text("Start Game")
        }
    }
}
