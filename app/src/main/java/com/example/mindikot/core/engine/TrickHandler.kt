package com.example.mindikot.core.engine

import com.example.mindikot.core.model.*

object TrickHandler {
    /**
     * Determines the winner of a completed trick based on Mindikot rules.
     *
     * @param playedCards A list of (Player, Card) pairs representing the cards played in the trick,
     * in order.
     * @param trumpSuit The active trump suit for the round (can be null if trump is not set).
     * @return The Player who won the trick.
     */
    fun determineTrickWinner(playedCards: List<Pair<Player, Card>>, trumpSuit: Suit?): Player {
        require(playedCards.isNotEmpty()) { "Cannot determine winner of an empty trick." }

        val leadSuit = playedCards.first().second.suit
        // var winningPlay: Pair<Player, Card> = playedCards.first() // Assume leader wins initially
        var winningPlay: Pair<Player, Card>

        // Check for highest trump card first
        val trumpPlays = playedCards.filter { it.second.suit == trumpSuit && trumpSuit != null }
        if (trumpPlays.isNotEmpty()) {
            winningPlay = trumpPlays.maxByOrNull { it.second.rank.value }!!
        } else {
            // No trump played, check for highest card of the lead suit
            val leadSuitPlays = playedCards.filter { it.second.suit == leadSuit }
            // We know leadSuitPlays is not empty because the leader played one.
            winningPlay = leadSuitPlays.maxByOrNull { it.second.rank.value }!!
        }

        return winningPlay.first // Return the winning player
    }
}
