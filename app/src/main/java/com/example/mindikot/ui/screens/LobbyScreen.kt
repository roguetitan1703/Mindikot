package com.example.mindikot.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.mindikot.ui.GameViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mindikot.core.model.GameMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign

@Composable
fun LobbyScreen(navController: NavController, viewModel: GameViewModel) {
    var playerName by remember { mutableStateOf("") }
    var isHost by remember { mutableStateOf(false) }
    var availableGames by remember { mutableStateOf(listOf<String>()) }
    var gameSelected by remember { mutableStateOf<String?>(null) }
    var allPlayersJoined by remember { mutableStateOf(false) }
    var hasSearched by remember { mutableStateOf(false) }
    var role by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current

    LaunchedEffect(playerName) {
        if (playerName.isBlank()) role = null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Mindikot Lobby", style = MaterialTheme.typography.headlineMedium)

        OutlinedTextField(
            value = playerName,
            onValueChange = {
                playerName = it
                viewModel.setPlayerName(it)
            },
            label = { Text("Enter your name") },
            modifier = Modifier.fillMaxWidth()
        )

        Text("Select your role:", style = MaterialTheme.typography.titleMedium)

        RoleSelector(
            selectedRole = role,
            onRoleSelected = { selected ->
                if (playerName.isBlank()) {
                    Toast.makeText(context, "Please enter your name first", Toast.LENGTH_SHORT).show()
                } else {
                    role = selected
                    isHost = (selected == "Host")
                }
            }
        )

        when {
            isHost && role == "Host" -> {
                GameConfigCard(navController, viewModel, playerName)
            }

            role == "Joiner" -> {
                JoinGameSection(
                    hasSearched = hasSearched,
                    availableGames = availableGames,
                    onSearch = {
                        hasSearched = true
                        availableGames = listOf() // Replace with actual discovery logic
                    },
                    onJoin = {
                        gameSelected = it
                        navController.navigate("waiting_for_players")
                    }
                )
            }
        }

        if (isHost && allPlayersJoined) {
            Button(onClick = { navController.navigate("game") }) {
                Text("Start Game")
            }
        }
    }
}

@Composable
fun RoleSelector(selectedRole: String?, onRoleSelected: (String) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf("Host", "Joiner").forEach { role ->
            Button(
                onClick = { onRoleSelected(role) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selectedRole == role) MaterialTheme.colorScheme.primary else Color.Gray
                )
            ) {
                Text(if (role == "Joiner") "Randi" else "Host")
            }
        }
    }
}

@Composable
fun JoinGameSection(
    hasSearched: Boolean,
    availableGames: List<String>,
    onSearch: () -> Unit,
    onJoin: (String) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Join a game lobby", style = MaterialTheme.typography.titleMedium)

        Button(onClick = onSearch) {
            Text("Search for Games")
        }

        if (hasSearched) {
            if (availableGames.isEmpty()) {
                Text(
                    text = "No lobby found",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                availableGames.forEach { game ->
                    Button(
                        onClick = { onJoin(game) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(game)
                    }
                }
            }
        }
    }
}

@Composable
fun GameConfigCard(
    navController: NavController,
    viewModel: GameViewModel,
    playerName: String
) {
    fun GameMode.displayName(): String = when (this) {
        GameMode.CHOOSE_WHEN_EMPTY -> "Choose When Empty"
        GameMode.FIRST_CARD_HIDDEN -> "First Card Hidden"
    }

    var numberOfPlayers by remember { mutableStateOf(4) }
    var gameMode by remember { mutableStateOf(GameMode.CHOOSE_WHEN_EMPTY) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
        elevation = CardDefaults.cardElevation(8.dp)
    ) {
        // Add scroll state
        val scrollState = rememberScrollState()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                text = "🎮 Game Configuration",
                style = MaterialTheme.typography.headlineSmall
            )

            Divider()

            // 🔢 Player Count Selection
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Select Number of Players", style = MaterialTheme.typography.titleMedium)

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    listOf(4, 6).forEach { count ->
                        ElevatedButton(
                            onClick = { numberOfPlayers = count },
                            colors = ButtonDefaults.elevatedButtonColors(
                                containerColor = if (numberOfPlayers == count)
                                    MaterialTheme.colorScheme.primary
                                else Color.LightGray
                            )
                        ) {
                            Text(
                                "$count Players",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (numberOfPlayers == count) Color.White else Color.Black
                            )
                        }
                    }
                }
            }

            Divider()

            // 🕹️ Game Mode Selection
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Select Game Mode", style = MaterialTheme.typography.titleMedium)

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    GameMode.entries.forEach { mode ->
                        ElevatedButton(
                            onClick = { gameMode = mode },
                            modifier = Modifier.width(160.dp), // Adjust width as needed
                            colors = ButtonDefaults.elevatedButtonColors(
                                containerColor = if (gameMode == mode)
                                    MaterialTheme.colorScheme.primary
                                else Color.LightGray
                            )
                        ) {
                            Text(
                                text = mode.displayName(),
                                style = MaterialTheme.typography.labelLarge,
                                color = if (gameMode == mode) Color.White else Color.Black,
                                maxLines = 2,
                                textAlign = TextAlign.Center
                            )
                        }

                    }
                }
            }

            Divider()

            // 📝 Summary
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "✅ Players: $numberOfPlayers",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = "✅ Mode: ${gameMode.displayName()}",
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            // 🚀 Confirm
            Button(
                onClick = {
                    viewModel.setupGame(playerName, gameMode, host = true, playersNeeded = numberOfPlayers)
                    navController.navigate("game_host")
                },
                enabled = numberOfPlayers in listOf(4, 6),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            ) {
                Text("Create Game")
            }
        }
    }
}
