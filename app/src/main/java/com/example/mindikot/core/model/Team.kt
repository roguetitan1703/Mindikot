package com.example.mindikot.core.model

// Uses immutable List for collectedCards
data class Team(
    val id: Int,
    val players: List<Player>, // Assuming Player is now immutable
    val collectedCards: List<Card> = emptyList() // Changed to immutable List
) {
    // These functions remain read-only, no changes needed
    fun countTens(): Int = collectedCards.count { it.rank == Rank.TEN }
    fun hasKot(): Boolean = countTens() == 4
}