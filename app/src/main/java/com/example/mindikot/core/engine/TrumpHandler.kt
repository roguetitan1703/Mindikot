package com.example.mindikot.core.engine

import com.example.mindikot.core.model.Card
import com.example.mindikot.core.model.Player
import com.example.mindikot.core.model.Suit
import com.example.mindikot.core.model.GameMode
import com.example.mindikot.core.state.GameState

object TrumpHandler {
    fun handleTrumpSelection(
        state: GameState,
        player: Player,
        chosenSuit: Suit? = null,
        passDiscard: Card? = null
    ) {
        when (state.gameMode) {
            GameMode.CHOOSE_WHEN_EMPTY -> {
                require(chosenSuit != null) { "Must choose a suit in CHOOSE_WHEN_EMPTY mode." }
                state.trumpSuit = chosenSuit
                state.trumpRevealed = true
            }
            GameMode.FIRST_CARD_HIDDEN -> {
                if (!state.trumpRevealed) {
                    if (state.hiddenCard == null) {
                        state.hiddenCard = player.hand.random().also { player.hand.remove(it) }
                    } else if (chosenSuit != null) {
                        state.trumpSuit = state.hiddenCard!!.suit
                        state.trumpRevealed = true
                    } else if (passDiscard != null) {
                        require(passDiscard.suit != state.hiddenCard!!.suit) { "Cannot discard a trump card." }
                        player.hand.remove(passDiscard)
                    }
                }
            }
        }
    }
}
