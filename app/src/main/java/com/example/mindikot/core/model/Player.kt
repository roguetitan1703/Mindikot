package com.example.mindikot.core.model

data class Player(
    val id: Int,
    val name: String,
    val isBot: Boolean = false,
    val teamId: Int,
    val hand: MutableList<Card> = mutableListOf()
)
