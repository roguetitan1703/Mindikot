package com.example.mindikot.core.engine

import com.example.mindikot.core.model.Card
import com.example.mindikot.core.model.Player
import com.example.mindikot.core.state.GameState

object TrickHandler {
    fun determineTrickWinner(played: List<Pair<Player, Card>>, state: GameState): Player {
        val trump = state.trumpSuit
        val trumpPlays = played.filter { it.second.suit == trump }
        val contenders = if (trump != null && trumpPlays.isNotEmpty()) {
            trumpPlays
        } else {
            val leadSuit = played.first().second.suit
            played.filter { it.second.suit == leadSuit }
        }
        return contenders.maxByOrNull { it.second.rank.value }!!.first
    }
}
