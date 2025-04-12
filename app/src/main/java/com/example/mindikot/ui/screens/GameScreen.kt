package com.example.mindikot.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.example.mindikot.ui.GameViewModel
import com.example.mindikot.core.model.Suit

@Composable
fun GameScreen(
    navController: NavHostController,
    viewModel: GameViewModel = viewModel()
) {
    val gameState by viewModel.state.collectAsState()
    LaunchedEffect(Unit) {
        viewModel.navigateToResultScreen.collect {
            navController.navigate("result")
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Trump Suit: ${gameState.trumpSuit ?: "Not chosen"}")

        Spacer(modifier = Modifier.height(12.dp))

        if (!gameState.trumpRevealed) {
            Text("Select Trump Suit:")
            Row {
                Suit.values().forEach { suit ->
                    Button(
                        onClick = { viewModel.selectTrump(suit) },
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text(suit.name)
                    }
                }
            }
        } else {
            Button(onClick = { viewModel.playTrick() }) {
                Text("Play Trick")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text("Players & Hands:")
        gameState.players.forEach { player ->
            Text("${player.name}: ${player.hand.size} cards")
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text("Collected Cards:")
        gameState.teams.forEach { team ->
            Text("Team ${team.id}: ${team.collectedCards.size} cards")
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = { viewModel.restartGame() }) {
            Text("Restart Game")
        }
    }
}
