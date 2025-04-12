package com.example.mindikot.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.example.mindikot.core.state.GameState

@Composable
fun GameStatus(gameState: GameState) {
    Column {
        Text("Round: ${gameState.gameMode}", style = MaterialTheme.typography.headlineLarge)
        Text("Current Leader: ${gameState.players[gameState.currentLeaderIndex].name}", style = MaterialTheme.typography.bodyMedium)
        Text("Trump Suit: ${gameState.trumpSuit?.name ?: "None"}", style = MaterialTheme.typography.bodyMedium)
    }
}
