package com.example.mindikot.core.engine

import com.example.mindikot.core.model.*

object DeckGenerator {
    /**
     * Generates a standard Mindikot deck. For 6 players, Twos are excluded.
     * @param numPlayers The number of players (2, 4 or 6).
     * @return An immutable, shuffled list of cards for the game.
     */
    fun generateDeck(numPlayers: Int): List<Card> { // Changed return type
        require(numPlayers == 4 || numPlayers == 6 || numPlayers == 2) { "Mindikot supports 2, 4 or 6 players only." }
        val includeTwos = (numPlayers == 4)

        val deck = mutableListOf<Card>() // Still use mutable locally for building
        val allSuits = Suit.values()
        val allRanks = Rank.values()

        for (suit in allSuits) {
            for (rank in allRanks) {
                // Skip Twos if playing with 6 players
                if (!includeTwos && rank == Rank.TWO) {
                    continue
                }
                deck.add(Card(suit, rank))
            }
        }
        // Shuffle the generated deck thoroughly before returning
        // shuffled() already returns a new List<Card>
        return deck.shuffled() // Return immutable List
    }
}