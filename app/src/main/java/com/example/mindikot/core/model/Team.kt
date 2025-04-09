package com.example.mindikot.core.model

data class Team(
    val id: Int,
    val players: List<Player>,
    var score: Int = 0,
    val capturedCards: MutableList<Card> = mutableListOf()
)
