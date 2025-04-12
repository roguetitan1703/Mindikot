package com.example.mindikot.core.model

data class Player(
    val id: Int,
    val name: String,
    val teamId: Int,
    var hand: MutableList<Card> = mutableListOf()
) {
    override fun toString() = "Player(id=$id, name=$name, team=$teamId)"
}
