package com.example.mindikot.core.engine

import com.example.mindikot.core.model.*
import com.example.mindikot.core.state.GameState
import com.example.mindikot.core.state.InputType

/**
 * Manages the core game flow, including playing tricks and handling state transitions. NOTE: This
 * implementation outlines the logic flow. A real implementation would need mechanisms (like
 * callbacks, suspend functions, or StateFlow) to handle asynchronous player input from the
 * UI/ViewModel layer. The points requiring input are marked.
 */
object GameEngine {

    /**
     * Determines the set of cards a player can legally play on their turn.
     *
     * @param playerHand The list of cards currently in the player's hand.
     * @param currentTrickPlays The list of (Player, Card) pairs already played in the current
     * trick.
     * @param trumpSuit The current trump suit (null if not set).
     * @param trumpRevealed Whether trump has been revealed.
     * @return A list of cards from the player's hand that are valid to play.
     */
    fun determineValidMoves(
            playerHand: List<Card>,
            currentTrickPlays: List<Pair<Player, Card>>,
            trumpSuit: Suit?,
            trumpRevealed: Boolean
    ): List<Card> {

        // If player is leading the trick (no cards played yet)
        if (currentTrickPlays.isEmpty()) {
            return playerHand // Can lead any card
        }

        val leadCard = currentTrickPlays.first().second
        val leadSuit = leadCard.suit

        // Check if player can follow the lead suit
        val cardsInLeadSuit = playerHand.filter { it.suit == leadSuit }
        if (cardsInLeadSuit.isNotEmpty()) {
            return cardsInLeadSuit // Must follow suit
        }

        // --- Cannot follow suit ---

        // Check if trump is revealed and active
        if (trumpRevealed && trumpSuit != null) {
            // Rule: If cannot follow suit and trump is set, play ANY card.
            return playerHand
        } else {
            // Rule: If cannot follow suit and trump is NOT set, play ANY card.
            // The decision to CHOOSE/REVEAL/PASS happens separately.
            // Special case: Mode B Reveal requires trump play if possible AFTER reveal.
            // This function determines playable cards BEFORE that decision point.
            return playerHand
        }
    }

    // --- Interaction State Management Functions ---

    /**
     * Prepares the game state to request input from the appropriate player. This should be called
     * at the start of a trick or after a player has played.
     *
     * @param state The current GameState to modify.
     * @param playerIndex The index of the player whose turn it is.
     * @return The modified GameState with updated input requirements.
     */
    fun requestInput(state: GameState, playerIndex: Int): GameState {
        val currentPlayer = state.players[playerIndex]

        if (state.currentTrickPlays.isEmpty()) { // Leading the trick
            state.requiredInputType = InputType.PLAY_CARD
        } else {
            val leadSuit = state.currentTrickPlays.first().second.suit
            val canFollowSuit = currentPlayer.hand.any { it.suit == leadSuit }

            if (canFollowSuit) {
                state.requiredInputType = InputType.PLAY_CARD
            } else { // Cannot follow suit
                if (state.trumpRevealed) {
                    state.requiredInputType = InputType.PLAY_CARD // Play any card
                } else { // Trump not revealed - need trump decision
                    when (state.gameMode) {
                        GameMode.CHOOSE_WHEN_EMPTY ->
                                state.requiredInputType =
                                        InputType.CHOOSE_TRUMP_SUIT // Player must play card to set
                        // trump
                        GameMode.FIRST_CARD_HIDDEN ->
                                state.requiredInputType =
                                        InputType.REVEAL_OR_PASS // Player chooses Reveal or Pass
                    }
                }
            }
        }
        state.awaitingInputFromPlayerIndex = playerIndex
        println(
                "GameEngine: Requesting ${state.requiredInputType} from Player ${state.players[playerIndex].name}"
        ) // Logging
        return state
    }

    /**
     * Processes the input received from a player (card played or trump decision). Validates the
     * input against the rules and current state. Advances the game state (plays card, sets trump,
     * finishes trick/round).
     *
     * @param currentState The current state of the game.
     * @param playerInput The input received from the player (e.g., Card object, Decision enum).
     * @return An updated GameState. The state might indicate further input is needed from the next
     * player, or that the round/game ended.
     */
    fun processPlayerInput(
            currentState: GameState,
            playerInput:
                    Any // Could be Card, Decision (Reveal/Pass), Suit (for potential future Mode A
            // choice)
            ): GameState {

        val playerIndex =
                currentState.awaitingInputFromPlayerIndex
                        ?: run {
                            println("Error: processPlayerInput called when no input was expected.")
                            return currentState // Or throw error
                        }
        val currentPlayer = currentState.players[playerIndex]
        val currentRequirement = currentState.requiredInputType

        println(
                "GameEngine: Processing input from Player ${currentPlayer.name}, expected: $currentRequirement, received: $playerInput"
        ) // Logging

        // --- Validate and Process Input ---
        when (currentRequirement) {
            InputType.CHOOSE_TRUMP_SUIT -> {
                // Mode A: Expecting a Card to be played which sets the trump
                val playedCard = playerInput as? Card
                if (playedCard == null || !currentPlayer.hand.contains(playedCard)) {
                    println("Error: Invalid card provided for CHOOSE_TRUMP_SUIT.")
                    return currentState // Re-request input
                }
                // Validate it's a valid play (must be unable to follow suit)
                val leadSuit = currentState.currentTrickPlays.firstOrNull()?.second?.suit
                if (leadSuit != null && currentPlayer.hand.any { it.suit == leadSuit }) {
                    println("Error: Player could follow suit, should not be choosing trump.")
                    return currentState // State logic error or invalid input sequence
                }

                TrumpHandler.setTrumpFromPlayedCard(currentState, playedCard)
                playCard(currentState, currentPlayer, playedCard) // Play the card
            }
            InputType.REVEAL_OR_PASS -> {
                // Mode B: Expecting a Decision (Reveal or Pass)
                when (playerInput as? Decision) {
                    Decision.REVEAL -> {
                        TrumpHandler.revealHiddenTrump(currentState)
                        // Now need player to play (Must play trump if possible)
                        currentState.requiredInputType = InputType.PLAY_CARD
                        currentState.awaitingInputFromPlayerIndex =
                                playerIndex // Still this player's turn
                        println(
                                "GameEngine: Player revealed trump. Requesting card play (must play trump if possible)."
                        )
                        return currentState // Request card input with reveal constraint
                    }
                    Decision.PASS -> {
                        TrumpHandler.handleTrumpPass(currentState)
                        // Player plays ANY card
                        currentState.requiredInputType = InputType.PLAY_CARD
                        currentState.awaitingInputFromPlayerIndex =
                                playerIndex // Still this player's turn
                        println("GameEngine: Player passed trump. Requesting any card play.")
                        return currentState // Request card input (any card valid)
                    }
                    else -> {
                        println("Error: Invalid decision provided for REVEAL_OR_PASS.")
                        return currentState // Re-request input
                    }
                }
            }
            InputType.PLAY_CARD -> {
                val playedCard = playerInput as? Card
                if (playedCard == null || !currentPlayer.hand.contains(playedCard)) {
                    println("Error: Invalid card provided for PLAY_CARD.")
                    return currentState // Re-request input
                }

                // --- Complex Validation for PLAY_CARD ---
                val leadSuit = currentState.currentTrickPlays.firstOrNull()?.second?.suit
                val canFollowSuit =
                        if (leadSuit != null) currentPlayer.hand.any { it.suit == leadSuit }
                        else false

                // 1. Must follow suit if possible?
                if (leadSuit != null && playedCard.suit != leadSuit && canFollowSuit) {
                    println(
                            "Error: Player must follow suit $leadSuit but played ${playedCard.suit}."
                    )
                    return currentState // Re-request input
                }

                // 2. Mode B Post-Reveal Constraint: Must play trump if revealed and possible?
                // Need a way to know if this PLAY_CARD followed a REVEAL action in the same turn
                // cycle...
                // This architecture makes tracking that tricky. A state machine or temporary flag
                // might be needed.
                // Assuming for now the UI/ViewModel layer handles this constraint check *before*
                // sending.

                // If validation passes:
                playCard(currentState, currentPlayer, playedCard)
            }
            null -> {
                println("Error: processPlayerInput called when requiredInputType is null.")
                return currentState
            }
        }

        // --- Post-Play State Update ---
        currentState.requiredInputType = null // Reset requirement after successful processing
        currentState.awaitingInputFromPlayerIndex = null // Clear awaiting player

        // Check if trick is complete
        if (currentState.currentTrickPlays.size == currentState.players.size) {
            finishTrick(
                    currentState
            ) // Determine winner, collect cards, update tricksWon, set next leader

            // Check if round ended (hands are empty)
            if (currentState.players.first().hand.isEmpty()) {
                println("GameEngine: Round finished.")
                // --- End of Round ---
                // Caller (ViewModel) should check hand size and call RoundEvaluator
                // and handle scoring/next round setup.
            } else {
                // Start next trick by requesting input from the new leader
                return requestInput(currentState, currentState.currentLeaderIndex)
            }
        } else {
            // Trick continues, request input from the next player
            val nextPlayerIndex = (playerIndex + 1) % currentState.players.size
            return requestInput(currentState, nextPlayerIndex)
        }

        return currentState // Return the final state after processing
    }

    /** Helper to add card to trick plays and remove from hand */
    private fun playCard(state: GameState, player: Player, card: Card) {
        state.currentTrickPlays.add(player to card)
        player.hand.remove(card)
        println("GameEngine: Player ${player.name} played ${card}") // Logging
    }

    /** Called when a trick is complete to determine winner and collect cards. */
    private fun finishTrick(state: GameState) {
        println("GameEngine: Trick finished. Plays: ${state.currentTrickPlays.map { it.second }}")
        val winnerPlayer =
                TrickHandler.determineTrickWinner(state.currentTrickPlays, state.trumpSuit)
        val winnerIndex = state.players.indexOf(winnerPlayer)
        println("GameEngine: Trick winner: ${winnerPlayer.name}") // Logging

        // Collect cards for the winning team
        val winningTeam =
                state.teams.first {
                    it.id == winnerPlayer.teamId
                } // Assuming player.teamId is correct
        winningTeam.collectedCards.addAll(state.currentTrickPlays.map { it.second })
        // println("Team ${winningTeam.id} collected cards. Total tens: ${winningTeam.countTens()}")
        // // Logging

        // --- Update trick count ---
        val currentTrickCount = state.tricksWon.getOrDefault(winningTeam.id, 0)
        state.tricksWon[winningTeam.id] = currentTrickCount + 1 // Increment trick count
        println(
                "GameEngine: Team ${winningTeam.id} tricks won this round: ${state.tricksWon[winningTeam.id]}"
        ) // Logging trick count

        // Clear trick plays and set next leader
        state.currentTrickPlays.clear()
        state.currentLeaderIndex = winnerIndex

        // Reset input requirement for the start of the next trick
        // The requestInput function will be called next, setting the specific input type.
        state.requiredInputType = null
        state.awaitingInputFromPlayerIndex = null
    }

    // Enum for Reveal/Pass decision
    enum class Decision {
        REVEAL,
        PASS
    }
}
