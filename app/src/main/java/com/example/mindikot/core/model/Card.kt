package com.example.mindikot.core.model

data class Card(val suit: Suit, val rank: Rank) {
    override fun toString(): String = "${rank.displayName} of ${suit}"
}
