package com.example.mindikot.core.state

import com.example.mindikot.core.model.Player
import com.example.mindikot.core.model.Team
import com.example.mindikot.core.model.Suit
import com.example.mindikot.core.model.GameMode
import com.example.mindikot.core.model.Card

data class GameState(
    val players: List<Player>,
    val teams: List<Team>,
    val gameMode: GameMode,
    var currentLeaderIndex: Int = 0,
    var trumpSuit: Suit? = null,
    var trumpRevealed: Boolean = false,
    var hiddenCard: Card? = null
)
