package com.example.mindikot.core.engine

import com.example.mindikot.core.model.*
import com.example.mindikot.core.state.GameState

object GameEngine {
    /**
     * Play a single trick, collect cards, and return next leader index.
     *
     * @param state current GameState
     * @param leaderIndex index of player leading this trick
     * @return index of trick winner
     */
    fun playTrick(state: GameState, leaderIndex: Int): Int {
        val trickPlays = mutableListOf<Pair<Player, Card>>()
        val players = state.players
        val trump = state.trumpSuit

        // Each player plays top card
        for (i in players.indices) {
            val idx = (leaderIndex + i) % players.size
            val player = players[idx]
            val card = player.hand.removeAt(0)
            trickPlays.add(player to card)
        }

        // Determine winner and collect cards
        val winner = TrickHandler.determineTrickWinner(trickPlays, trump)
        state.teams.first { it.id == winner.teamId }
            .collectedCards.addAll(trickPlays.map { it.second })

        return players.indexOf(winner)
    }
}
