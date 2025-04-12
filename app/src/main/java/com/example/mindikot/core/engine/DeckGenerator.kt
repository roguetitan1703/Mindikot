package com.example.mindikot.core.engine

import com.example.mindikot.core.model.*

object DeckGenerator {
    /**
     * Generates a standard Mindikot deck. For 6 players, Twos are excluded.
     * @param numPlayers The number of players (4 or 6).
     * @return A shuffled list of cards for the game.
     */
    fun generateDeck(numPlayers: Int): MutableList<Card> {
        require(numPlayers == 4 || numPlayers == 6) { "Mindikot supports 4 or 6 players only." }
        val includeTwos = (numPlayers == 4)

        val deck = mutableListOf<Card>()
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
        return deck.shuffled().toMutableList()
    }
}
