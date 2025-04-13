package com.example.mindikot.core.engine

import com.example.mindikot.core.model.*
import com.example.mindikot.core.state.GameState
import com.example.mindikot.core.state.InputType

/**
 * Manages the core game flow using immutable state transitions.
 * Functions take a GameState and return a new GameState reflecting the changes.
 */
object GameEngine {

    /**
     * Determines the set of cards a player can legally play on their turn.
     * (This function is already pure and needs no changes)
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

        // Rule: If cannot follow suit, can play any card.
        // Specific restrictions (like choosing trump or revealing) are handled by input type requirement.
        return playerHand
    }

    // --- Interaction State Management Functions ---

    /**
     * Calculates the game state required to request input from the appropriate player.
     *
     * @param state The current GameState.
     * @param playerIndex The index of the player whose turn it is.
     * @return A new GameState with updated input requirements.
     */
    fun requestInput(state: GameState, playerIndex: Int): GameState {
        val currentPlayer = state.players[playerIndex]
        val requiredInputType: InputType?

        if (state.currentTrickPlays.isEmpty()) { // Leading the trick
            requiredInputType = InputType.PLAY_CARD
        } else {
            val leadSuit = state.currentTrickPlays.first().second.suit
            val canFollowSuit = currentPlayer.hand.any { it.suit == leadSuit }

            if (canFollowSuit) {
                requiredInputType = InputType.PLAY_CARD
            } else { // Cannot follow suit
                if (state.trumpRevealed) {
                    requiredInputType = InputType.PLAY_CARD // Play any card
                } else { // Trump not revealed - need trump decision
                    requiredInputType = when (state.gameMode) {
                        GameMode.CHOOSE_WHEN_EMPTY -> InputType.CHOOSE_TRUMP_SUIT // Play card sets trump
                        GameMode.FIRST_CARD_HIDDEN -> InputType.REVEAL_OR_PASS // Choose Reveal or Pass
                    }
                }
            }
        }
        println(
            "GameEngine: Requesting $requiredInputType from Player ${state.players[playerIndex].name}"
        ) // Logging
        // Return new state with input requirements set
        return state.copy(
            awaitingInputFromPlayerIndex = playerIndex,
            requiredInputType = requiredInputType
        )
    }

    /**
     * Processes the input received from a player (card played or trump decision). Validates the
     * input against the rules and current state. Calculates the next game state (card played, trump set,
     * trick/round finished).
     *
     * @param currentState The current state of the game.
     * @param playerInput The input received from the player (e.g., Card object, Decision enum).
     * @return An updated GameState. The state might indicate further input is needed from the next
     * player, or that the round/game ended.
     * @throws IllegalStateException if the move is invalid according to game rules or input is unexpected.
     */
    fun processPlayerInput(
        currentState: GameState,
        playerInput: Any // Card, Decision
    ): GameState {

        val playerIndex =
            currentState.awaitingInputFromPlayerIndex
                ?: throw IllegalStateException("processPlayerInput called when no input was expected.")

        val currentPlayer = currentState.players[playerIndex]
        val currentRequirement = currentState.requiredInputType
            ?: throw IllegalStateException("processPlayerInput called when requiredInputType is null.")


        println(
            "GameEngine: Processing input from Player ${currentPlayer.name}, expected: $currentRequirement, received: $playerInput"
        ) // Logging

        var nextState = currentState // Start with current state, apply changes immutably

        // --- Validate and Process Input ---
        when (currentRequirement) {
            InputType.CHOOSE_TRUMP_SUIT -> {
                val playedCard = playerInput as? Card
                    ?: throw IllegalStateException("Invalid input type for CHOOSE_TRUMP_SUIT. Expected Card, got ${playerInput::class.simpleName}")
                if (!currentPlayer.hand.contains(playedCard)) {
                    throw IllegalStateException("Card $playedCard not in player ${currentPlayer.name}'s hand.")
                }
                // Validate it's a valid play (must be unable to follow suit)
                val leadSuit = currentState.currentTrickPlays.firstOrNull()?.second?.suit
                if (leadSuit != null && currentPlayer.hand.any { it.suit == leadSuit }) {
                    throw IllegalStateException("Player ${currentPlayer.name} could follow suit $leadSuit, cannot choose trump now.")
                }

                // Set trump based on the played card
                nextState = TrumpHandler.setTrumpFromPlayedCard(nextState, playedCard)
                // Play the card (updates players, currentTrickPlays)
                nextState = applyPlayCard(nextState, playerIndex, playedCard)
            }
            InputType.REVEAL_OR_PASS -> {
                when (playerInput as? Decision) {
                    Decision.REVEAL -> {
                        nextState = TrumpHandler.revealHiddenTrump(nextState)
                        // State now requires this player to PLAY_CARD (with reveal constraint)
                        // The requestInput after this will determine validity based on revealed trump
                        nextState = nextState.copy(
                            requiredInputType = InputType.PLAY_CARD,
                            awaitingInputFromPlayerIndex = playerIndex // Still this player's turn
                        )
                        println(
                            "GameEngine: Player revealed trump. Requesting card play (must play trump if possible)."
                        )
                        return nextState // Return state requesting card play, *before* clearing flags
                    }
                    Decision.PASS -> {
                        nextState = TrumpHandler.handleTrumpPass(nextState)
                        // State now requires this player to PLAY_CARD (any card)
                        nextState = nextState.copy(
                            requiredInputType = InputType.PLAY_CARD,
                            awaitingInputFromPlayerIndex = playerIndex // Still this player's turn
                        )
                        println("GameEngine: Player passed trump. Requesting any card play.")
                        return nextState // Return state requesting card play, *before* clearing flags
                    }
                    null -> throw IllegalStateException("Invalid input type for REVEAL_OR_PASS. Expected Decision, got ${playerInput::class.simpleName}")
                }
            }
            InputType.PLAY_CARD -> {
                val playedCard = playerInput as? Card
                    ?: throw IllegalStateException("Invalid input type for PLAY_CARD. Expected Card, got ${playerInput::class.simpleName}")
                if (!currentPlayer.hand.contains(playedCard)) {
                    throw IllegalStateException("Card $playedCard not in player ${currentPlayer.name}'s hand: ${currentPlayer.hand}")
                }

                // --- Complex Validation for PLAY_CARD ---
                 val validMoves = determineValidMoves(
                     playerHand = currentPlayer.hand,
                     currentTrickPlays = currentState.currentTrickPlays,
                     trumpSuit = currentState.trumpSuit, // Use current state's trump for validation
                     trumpRevealed = currentState.trumpRevealed // Use current state's reveal status
                 )

                 if (!validMoves.contains(playedCard)) {
                     // Check specific constraint for Mode B after REVEAL
                     val justRevealed = currentState.requiredInputType == InputType.PLAY_CARD &&
                                       currentState.awaitingInputFromPlayerIndex == playerIndex &&
                                       currentState.trumpRevealed && // Trump is now revealed
                                       currentState.gameMode == GameMode.FIRST_CARD_HIDDEN
                                       // We need a better way to know if REVEAL *just* happened.
                                       // Let's assume if trump is revealed and mode is B, and we expect PLAY_CARD
                                       // from the same player, they *might* have just revealed.

                     val mustPlayTrump = justRevealed && // Simplified condition
                                       currentState.trumpSuit != null &&
                                       currentPlayer.hand.any { it.suit == currentState.trumpSuit }

                     if (mustPlayTrump && playedCard.suit != currentState.trumpSuit) {
                         throw IllegalStateException("Invalid move after revealing. Must play trump ${currentState.trumpSuit} if possible. Played $playedCard. Hand: ${currentPlayer.hand}")
                     } else if (!validMoves.contains(playedCard)) {
                          // General invalid move (e.g., didn't follow suit when possible)
                          throw IllegalStateException("Invalid move. Player ${currentPlayer.name} played $playedCard. Valid moves: $validMoves. Hand: ${currentPlayer.hand}")
                     }
                 }

                // If validation passes:
                nextState = applyPlayCard(nextState, playerIndex, playedCard)
            }
        }

        // --- Post-Play State Update ---
        // Flags are cleared within applyPlayCard or applyFinishTrick returns state with cleared flags

        // Check if trick is complete
        if (nextState.currentTrickPlays.size == nextState.players.size) {
            val stateAfterTrick = applyFinishTrick(nextState) // Determine winner, update state, set next leader

            // Check if round ended (hands are empty - check first player's hand)
            if (stateAfterTrick.players.firstOrNull()?.hand?.isEmpty() == true) {
                println("GameEngine: Round finished.")
                // End of Round: State is final for this round. Caller (ViewModel) handles evaluation.
                return stateAfterTrick // Return final round state (input flags already cleared)
            } else {
                // Start next trick: Request input from the new leader
                return requestInput(stateAfterTrick, stateAfterTrick.currentLeaderIndex)
            }
        } else {
            // Trick continues: Request input from the next player
            val nextPlayerIndex = (playerIndex + 1) % nextState.players.size
            return requestInput(nextState, nextPlayerIndex)
        }
    }

    /** Helper to calculate the state after a card is played */
    private fun applyPlayCard(state: GameState, playerIndex: Int, card: Card): GameState {
        val player = state.players[playerIndex]
        println("GameEngine: Player ${player.name} played $card") // Logging

        // 1. Create updated hand for the player
        val updatedHand = player.hand - card // Returns new list without the card

        // 2. Create updated player object
        val updatedPlayer = player.copy(hand = updatedHand)

        // 3. Create updated players list
        val updatedPlayers = state.players.toMutableList().apply {
            set(playerIndex, updatedPlayer)
        }.toList() // Make immutable again

        // 4. Create updated trick plays list
        val updatedTrickPlays = state.currentTrickPlays + (updatedPlayer to card) // Add new pair (use updatedPlayer ref)

        // 5. Return new state with updated player list, trick plays, and cleared input request
        return state.copy(
            players = updatedPlayers,
            currentTrickPlays = updatedTrickPlays,
            awaitingInputFromPlayerIndex = null, // Input processed
            requiredInputType = null
        )
    }

    /** Helper to calculate the state after a trick is finished */
    private fun applyFinishTrick(state: GameState): GameState {
        println("GameEngine: Trick finished. Plays: ${state.currentTrickPlays.map { it.second }}")
        val trickPlays = state.currentTrickPlays // Already immutable List<Pair<Player, Card>>

        // 1. Determine Winner (using player references from the trickPlays)
        val winnerPlayerRef = TrickHandler.determineTrickWinner(trickPlays, state.trumpSuit)
        // Find the corresponding player *object* in the main state list to get updated info if needed
        val winnerPlayerState = state.players.first { it.id == winnerPlayerRef.id }
        val winnerIndex = state.players.indexOf(winnerPlayerState) // Find index by object identity
        val winningTeamId = winnerPlayerState.teamId
        println("GameEngine: Trick winner: ${winnerPlayerState.name} (Index: $winnerIndex, Team: $winningTeamId)")

        // 2. Update Winning Team's Collected Cards
        val winningTeam = state.teams.first { it.id == winningTeamId }
        val cardsToAdd = trickPlays.map { it.second } // Extract cards from the trick
        val updatedCollectedCards = winningTeam.collectedCards + cardsToAdd // Create new list
        val updatedWinningTeam = winningTeam.copy(collectedCards = updatedCollectedCards) // Create new team object

        // 3. Create Updated Teams List
        val updatedTeams = state.teams.map { team ->
            if (team.id == winningTeamId) updatedWinningTeam else team // Replace winning team
        }

        // 4. Update Tricks Won Map
        val currentTrickCount = state.tricksWon.getOrDefault(winningTeamId, 0)
        val updatedTricksWon = state.tricksWon + (winningTeamId to currentTrickCount + 1) // Creates new map

        println(
            "GameEngine: Team $winningTeamId tricks won this round: ${updatedTricksWon[winningTeamId]}"
        )

        // 5. Return new state with cleared trick, updated teams/tricksWon, new leader, and cleared input request
        return state.copy(
            teams = updatedTeams,
            tricksWon = updatedTricksWon,
            currentTrickPlays = emptyList(), // Clear trick plays for next trick
            currentLeaderIndex = winnerIndex, // Set next leader
            awaitingInputFromPlayerIndex = null, // Clear awaiting player after trick finish
            requiredInputType = null
        )
    }

    // Enum for Reveal/Pass decision
    enum class Decision {
        REVEAL,
        PASS
    }
}