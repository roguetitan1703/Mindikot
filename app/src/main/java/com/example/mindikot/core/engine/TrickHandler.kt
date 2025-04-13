package com.example.mindikot.core.engine

import com.example.mindikot.core.model.*

object TrickHandler {
    /**
     * Determines the winner of a completed trick based on Mindikot rules.
     * This function is pure and reads immutable data.
     *
     * @param playedCards A list of (Player, Card) pairs representing the cards played in the trick,
     * in order. Assumes Player objects within the pairs are consistent references.
     * @param trumpSuit The active trump suit for the round (can be null if trump is not set).
     * @return The Player object (reference from the input list) who won the trick.
     */
    fun determineTrickWinner(playedCards: List<Pair<Player, Card>>, trumpSuit: Suit?): Player {
        require(playedCards.isNotEmpty()) { "Cannot determine winner of an empty trick." }

        val leadSuit = playedCards.first().second.suit
        var winningPlay: Pair<Player, Card> // Will hold the reference to the winning Pair

        // Check for highest trump card first
        val trumpPlays = playedCards.filter { it.second.suit == trumpSuit && trumpSuit != null }
        if (trumpPlays.isNotEmpty()) {
            // Find the Pair with the highest ranking trump card
            winningPlay = trumpPlays.maxByOrNull { it.second.rank.value }!! // Non-null assertion safe due to isNotEmpty check
        } else {
            // No trump played, check for highest card of the lead suit
            val leadSuitPlays = playedCards.filter { it.second.suit == leadSuit }
            // We know leadSuitPlays is not empty because the leader played one.
            // Find the Pair with the highest ranking card of the lead suit
            winningPlay = leadSuitPlays.maxByOrNull { it.second.rank.value }!! // Non-null assertion safe as list is not empty
        }

        // Return the Player part of the winning Pair
        return winningPlay.first
    }
}