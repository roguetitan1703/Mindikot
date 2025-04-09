package com.example.mindikot.core.engine

import com.example.mindikot.core.model.Card
import com.example.mindikot.core.model.Rank
import com.example.mindikot.core.model.Suit
import com.example.mindikot.core.model.Player

object DeckGenerator {
    fun generateDeck(includeTwos: Boolean = true): MutableList<Card> {
        val deck = mutableListOf<Card>()
        for (suit in Suit.values()) {
            for (rank in Rank.values()) {
                if (!includeTwos && rank == Rank.TWO) continue
                deck.add(Card(suit, rank))
            }
        }
        deck.shuffle()
        return deck
    }

    fun dealCards(players: List<Player>, deck: MutableList<Card>) {
        val handSize = deck.size / players.size
        players.forEach { player ->
            repeat(handSize) {
                deck.removeFirstOrNull()?.let(player.hand::add)
            }
        }
    }
}
