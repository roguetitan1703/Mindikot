package com.example.mindikot.core.engine

import com.example.mindikot.core.model.*

object DeckGenerator {
    /**
     * @param includeTwos true for 4‑player games (include TWO); false for 6‑player (exclude TWO).
     */
    fun generateDeck(includeTwos: Boolean): MutableList<Card> {
        val deck = mutableListOf<Card>()
        for (suit in Suit.values()) {
            for (rank in Rank.values()) {
                if (!includeTwos && rank == Rank.TWO) continue // fix: exclude TWO, not THREE
                deck.add(Card(suit, rank))
            }
        }
        return deck.shuffled().toMutableList()
    }
}
