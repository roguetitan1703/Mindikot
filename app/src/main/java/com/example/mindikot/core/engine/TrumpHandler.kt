package com.example.mindikot.core.engine

import com.example.mindikot.core.model.Card
import com.example.mindikot.core.model.GameMode
import com.example.mindikot.core.model.Suit // Added explicit import
import com.example.mindikot.core.state.GameState

/**
 * Contains pure functions to calculate new game states related to trump setting, based on player decisions.
 */
object TrumpHandler {

    /**
     * Calculates the new game state when a player chooses a trump suit in CHOOSE_WHEN_EMPTY mode
     * by playing a card that establishes the trump.
     * Precondition: state.trumpRevealed should be false.
     *
     * @param state The current GameState.
     * @param cardPlayed The card whose suit sets the trump.
     * @return A new GameState with the trump suit set and revealed.
     */
    fun setTrumpFromPlayedCard(state: GameState, cardPlayed: Card): GameState {
        return if (!state.trumpRevealed) {
            println(
                "TrumpHandler: Trump set to ${cardPlayed.suit} by card $cardPlayed"
            ) // Logging
            state.copy(trumpSuit = cardPlayed.suit, trumpRevealed = true)
        } else {
             println(
                "TrumpHandler: Trump already revealed (${state.trumpSuit}), cannot set again."
            ) // Logging
            state // No change if already revealed
        }
    }

    /**
     * Calculates the new game state when a player chooses to reveal the hidden card in FIRST_CARD_HIDDEN mode.
     * Precondition: state.trumpRevealed should be false and state.hiddenCard should not be null.
     *
     * @param state The current GameState.
     * @return A new GameState with the trump suit set to the hidden card's suit and revealed.
     */
    fun revealHiddenTrump(state: GameState): GameState {
        return if (!state.trumpRevealed &&
                   state.hiddenCard != null &&
                   state.gameMode == GameMode.FIRST_CARD_HIDDEN
        ) {
            println(
                "TrumpHandler: Trump revealed as ${state.hiddenCard.suit} from hidden card"
            ) // Logging
            state.copy(trumpSuit = state.hiddenCard.suit, trumpRevealed = true)
            // Note: GameEngine must enforce the special play rule for this turn based on the new state.
        } else {
             println(
                "TrumpHandler: Cannot reveal hidden trump (already revealed, no hidden card, or wrong mode)."
            ) // Logging
            state // No change if conditions not met
        }
    }

    /**
     * Acknowledges the "Pass" action in FIRST_CARD_HIDDEN mode. Returns the state unchanged
     * as the Pass action itself doesn't modify trump state.
     *
     * @param state The current GameState.
     * @return The original GameState instance.
     */
    fun handleTrumpPass(state: GameState): GameState {
        // No change to state.trumpSuit or state.trumpRevealed.
        println("TrumpHandler: Player chose to Pass.") // Logging
        // GameEngine allows the player to play any card for this trick based on the state returned.
        return state
    }
}