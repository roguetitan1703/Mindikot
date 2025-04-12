package com.example.mindikot

import androidx.lifecycle.ViewModel
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.example.mindikot.core.model.*
import com.example.mindikot.core.state.GameState

class GameViewModel : ViewModel() {
    var gameState by mutableStateOf(GameState(players = emptyList(), teams = emptyList(), gameMode = GameMode.CHOOSE_WHEN_EMPTY))
        private set

    var currentHand by mutableStateOf<List<Card>>(emptyList())

    fun playerMakesMove(playerId: Int, card: Card) {
        currentHand = currentHand.filter { it != card }
    }

    fun startNewRound() {
        // Reset game state and start a new round
    }

    fun updateGameState(newState: GameState) {
        gameState = newState
    }
}
