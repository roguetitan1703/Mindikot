package com.example.mindikot.core.model

// Uses immutable List for hand
data class Player(
    val id: Int,
    val name: String,
    val teamId: Int,
    val hand: List<Card> = emptyList() // Changed to immutable List
) {
    override fun toString() = "Player(id=$id, name=$name, team=$teamId)"
}