package com.example.mindikot.core.model

data class Team(
    val id: Int,
    val players: List<Player>,
    val collectedCards: MutableList<Card> = mutableListOf()
) {
    fun countTens(): Int = collectedCards.count { it.rank == Rank.TEN }
    fun hasKot(): Boolean = countTens() == 4
}
