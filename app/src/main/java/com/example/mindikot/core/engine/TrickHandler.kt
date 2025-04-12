package com.example.mindikot.core.engine

import com.example.mindikot.core.model.*

object TrickHandler {
    /**
     * Determine trick winner based on lead suit and trump.
     *
     * @param playedCards list of (Player to Card) in play order
     * @param trumpSuit current trump suit (or null)
     * @return winning Player
     */
    fun determineTrickWinner(
        playedCards: List<Pair<Player, Card>>,
        trumpSuit: Suit?
    ): Player {
        val leadSuit = playedCards.first().second.suit

        // Score each play: trump +100, lead-suit + rank, else 0
        val scored = playedCards.map { (player:Player, card:Card) ->
            val score = when {
                card.suit == trumpSuit -> card.rank.displayName.toInt() + 100
                card.suit == leadSuit -> card.rank.displayName.toInt()
                else -> 0
            }
            Triple(player, card, score)
        }

        // Highest score wins
        return scored.maxBy { it.third }.first
    }
}
