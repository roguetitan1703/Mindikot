package com.example.mindikot.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.example.mindikot.GameViewModel
import com.example.mindikot.core.model.Card
import com.example.mindikot.ui.components.CardView
import com.example.mindikot.ui.components.GameStatus
import com.example.mindikot.ui.components.PlayerHand

@Composable
fun GameScreen(navController: NavController, viewModel: GameViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Game Status
        GameStatus(gameState = viewModel.gameState)

        Spacer(modifier = Modifier.height(16.dp))

        // Player's Hand
        Text("Your Hand", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(8.dp))
        PlayerHand(cards = viewModel.currentHand) { selectedCard ->
            // Handle card selection (e.g., play the card)
            viewModel.playerMakesMove(playerId = 1, card = selectedCard)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Action Buttons
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = { /* Implement play card logic */ }) {
                Text("Play Card")
            }

            Button(onClick = { /* Implement pass logic */ }) {
                Text("Pass")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // End Round Button
        Button(onClick = { navController.navigate("result") }) {
            Text("End Round")
        }
    }
}

@Composable
@Preview(showBackground = true)
fun GameScreenPreview() {
    // Create a mock NavController and ViewModel for the preview
    val navController = rememberNavController()
    val mockViewModel = GameViewModel()  // Mock view model for preview
    GameScreen(navController = navController, viewModel = mockViewModel)
}
