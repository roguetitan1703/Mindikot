package com.example.mindikot.core.engine

import com.example.mindikot.core.state.GameState

/**
 * Contains simple helper functions to update the game state related to trump setting. These
 * functions are intended to be called by the GameEngine AFTER a player has made their trump-related
 * decision via external input (UI/ViewModel).
 */
object TrumpHandler {

    /**
     * Updates the game state when a player chooses a trump suit in CHOOSE_WHEN_EMPTY mode by
     * playing a card that establishes the trump. Precondition: state.trumpRevealed should be false.
     *
     * @param state The current GameState to modify.
     * @param cardPlayed The card whose suit sets the trump.
     */
    fun setTrumpFromPlayedCard(state: GameState, cardPlayed: com.example.mindikot.core.model.Card) {
        if (!state.trumpRevealed) {
            state.trumpSuit = cardPlayed.suit
            state.trumpRevealed = true
            println(
                    "TrumpHandler: Trump set to ${state.trumpSuit} by card ${cardPlayed}"
            ) // Logging
        }
    }

    /**
     * Updates the game state when a player chooses to reveal the hidden card in FIRST_CARD_HIDDEN
     * mode. Precondition: state.trumpRevealed should be false and state.hiddenCard should not be
     * null.
     *
     * @param state The current GameState to modify.
     */
    fun revealHiddenTrump(state: GameState) {
        if (!state.trumpRevealed &&
                        state.hiddenCard != null &&
                        state.gameMode == com.example.mindikot.core.model.GameMode.FIRST_CARD_HIDDEN
        ) {
            state.trumpSuit = state.hiddenCard!!.suit // Set trump to hidden card's suit
            state.trumpRevealed = true
            println(
                    "TrumpHandler: Trump revealed as ${state.trumpSuit} from hidden card"
            ) // Logging
            // Note: GameEngine must enforce the special play rule for this turn.
        }
    }

    /**
     * Placeholder function to acknowledge the "Pass" action in FIRST_CARD_HIDDEN mode. No state
     * change is needed regarding trump itself.
     *
     * @param state The current GameState (not modified here).
     */
    fun handleTrumpPass(state: GameState) {
        // No change to state.trumpSuit or state.trumpRevealed.
        println("TrumpHandler: Player chose to Pass.") // Logging
        // GameEngine allows the player to play any card for this trick.
    }
}
