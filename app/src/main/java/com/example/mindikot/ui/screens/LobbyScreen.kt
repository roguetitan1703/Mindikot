package com.example.mindikot.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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

@Composable
fun LobbyScreen(navController: NavController, viewModel: GameViewModel = viewModel()) {
    var playerName by remember { mutableStateOf("") }
    var isHost by remember { mutableStateOf(false) }
    var availableGames by remember { mutableStateOf(listOf<String>()) } // List of available games for joiners
    var gameSelected by remember { mutableStateOf<String?>(null) } // The game selected by the client
    var allPlayersJoined by remember { mutableStateOf(false) }
    var hasSearched by remember { mutableStateOf(false) } // Track if user initiated a search
    var role by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(playerName) {

        if (playerName.isBlank()) {
            role = null
        }
    }

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
            onValueChange = {
                playerName = it
                viewModel.setPlayerName(it) // 🔥 Update ViewModel when name is typed
            },
            label = { Text("Enter your name") }
        )

        val context = LocalContext.current // 🔥 Needed for toast

        Text("Are you a Host or Joiner?")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (playerName.isBlank()) {
                        Toast.makeText(context, "Please enter your name first", Toast.LENGTH_SHORT).show()
                    } else {
                        role = "Host"
                        isHost = true
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (role == "Host") MaterialTheme.colorScheme.primary else Color.Gray
                )
            ) {
                Text("Host")
            }

            Button(
                onClick = {
                    if (playerName.isBlank()) {
                        Toast.makeText(context, "Please enter your name first", Toast.LENGTH_SHORT).show()
                    } else {
                        role = "Joiner"
                        isHost = false
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (role == "Joiner") MaterialTheme.colorScheme.primary else Color.Gray
                )
            ) {
                Text("Joiner")
            }
        }


        // Host Flow
        if (isHost && role =="Host") {

            GameConfigCard(navController, viewModel,playerName) // Using GameConfigCard for configuration UI
        }

        // Joiner Flow
        else if (role=="Joiner"){
            Text("Select a game to join:")

            Button(onClick = {
                hasSearched = true
                // Simulated game search result (replace with actual discovery logic)
                availableGames = listOf() // or mock list e.g., listOf("Host Game 1")
            }) {
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
                        Button(onClick = {
                            gameSelected = game
                            navController.navigate("waiting_for_players")  // Navigate to waiting screen
                        }) {
                            Text(game)
                        }
                    }
                }
            }
        }

        // Button to start the game if all players are ready (host flow)
        if (isHost && allPlayersJoined) {
            Button(onClick = { navController.navigate("game") }) {
                Text("Start Game")
            }
        }
    }
}

@Composable
fun GameConfigCard(navController: NavController, viewModel: GameViewModel = viewModel(),playerName:String) {
    var numberOfPlayers by remember { mutableStateOf(4) }
    var gameMode by remember { mutableStateOf(GameMode.CHOOSE_WHEN_EMPTY) }

    // Card styling
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp) // Corrected elevation usage
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Game Configuration", style = MaterialTheme.typography.headlineSmall)

            // Number of Players Selection
            Text("Select Number of Players:", style = MaterialTheme.typography.bodyLarge)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { numberOfPlayers = 4 },
                    colors = ButtonDefaults.buttonColors(containerColor = if (numberOfPlayers == 4) MaterialTheme.colorScheme.primary else Color.Gray)
                ) {
                    Text("4 Players")
                }
                Button(
                    onClick = { numberOfPlayers = 6 },
                    colors = ButtonDefaults.buttonColors(containerColor = if (numberOfPlayers == 6) MaterialTheme.colorScheme.primary else Color.Gray)
                ) {
                    Text("6 Players")
                }
            }

            // Game Mode Selection
            Text("Select Game Mode:", style = MaterialTheme.typography.bodyLarge)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { gameMode = GameMode.CHOOSE_WHEN_EMPTY },
                    colors = ButtonDefaults.buttonColors(containerColor = if (gameMode == GameMode.CHOOSE_WHEN_EMPTY) MaterialTheme.colorScheme.primary else Color.Gray)
                ) {
                    Text("Choose When Empty")
                }
                Button(
                    onClick = { gameMode = GameMode.FIRST_CARD_HIDDEN },
                    colors = ButtonDefaults.buttonColors(containerColor = if (gameMode == GameMode.FIRST_CARD_HIDDEN) MaterialTheme.colorScheme.primary else Color.Gray)
                ) {
                    Text("First Card Hidden")
                }
            }

            // Display the selected number of players and game mode
            Text("Selected Players: $numberOfPlayers", style = MaterialTheme.typography.bodyMedium)
            Text("Selected Game Mode: ${gameMode.name}", style = MaterialTheme.typography.bodyMedium)

            // Confirm Button
            Button(
                onClick = {

                    navController.navigate("game_host") // Navigate to the game screen (waiting for players)
                    viewModel.setupGame(playerName, gameMode, host = true, playersNeeded = numberOfPlayers)

                },
                enabled = numberOfPlayers in listOf(4, 6)
            ) {
                Text("Create Game")
            }
        }
    }
}
