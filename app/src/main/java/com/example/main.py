import os
from pathlib import Path

# Define the base directory for the source files
# Adjust this path if your project structure is different
base_dir = Path("src/main/java/com/example/mindikot")

# Dictionary to hold the file paths (relative to base_dir) and their content
updated_files_content = {
    # --- model ---
    "core/model/Player.kt": """
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
""",
    "core/model/Team.kt": """
package com.example.mindikot.core.model

// Uses immutable List for collectedCards
data class Team(
    val id: Int,
    val players: List<Player>, // Assuming Player is now immutable
    val collectedCards: List<Card> = emptyList() // Changed to immutable List
) {
    // These functions remain read-only, no changes needed
    fun countTens(): Int = collectedCards.count { it.rank == Rank.TEN }
    fun hasKot(): Boolean = countTens() == 4
}
""",
    # --- state ---
    "core/state/GameState.kt": """
package com.example.mindikot.core.state

import com.example.mindikot.core.model.*

/**
 * Represents the complete, immutable state of the Mindikot game at any point in time.
 * Use the `copy()` method to create modified versions of the state.
 *
 * @property players List of all players participating in the game.
 * @property teams List of the teams, typically containing references to their players and collected cards.
 * @property gameMode The selected mode for trump determination.
 * @property currentLeaderIndex The index (in the `players` list) of the player who leads the current or next trick.
 * @property trumpSuit The suit designated as trump for the current round. Null if trump has not been set yet.
 * @property trumpRevealed Boolean flag indicating whether the trump suit has been determined and revealed.
 * @property hiddenCard In FIRST_CARD_HIDDEN mode, this holds the card set aside *before dealing*. Null otherwise.
 * @property currentTrickPlays The cards played so far in the trick-in-progress. List<Pair<Player, Card>>.
 * @property awaitingInputFromPlayerIndex Index of the player from whom input is currently awaited. Null otherwise.
 * @property requiredInputType The type of input needed from the awaiting player. Null otherwise.
 * @property tricksWon A map storing the number of tricks won by each team (TeamId -> Trick Count) in the current round.
 */
data class GameState(
    val players: List<Player>,          // Immutable Player assumed
    val teams: List<Team>,              // Immutable Team assumed
    val gameMode: GameMode,
    val currentLeaderIndex: Int = 0,           // Now val
    val trumpSuit: Suit? = null,               // Now val
    val trumpRevealed: Boolean = false,        // Now val
    val hiddenCard: Card? = null,              // Now val
    val currentTrickPlays: List<Pair<Player, Card>> = emptyList(), // Immutable List
    val awaitingInputFromPlayerIndex: Int? = null, // Now val
    val requiredInputType: InputType? = null,      // Now val
    val tricksWon: Map<Int, Int> = emptyMap()      // Immutable Map
)

/** Enum to represent the type of input currently required from a player. */
enum class InputType {
    PLAY_CARD,
    CHOOSE_TRUMP_SUIT,
    REVEAL_OR_PASS
}
""",
    # --- engine ---
    "core/engine/DeckGenerator.kt": """
package com.example.mindikot.core.engine

import com.example.mindikot.core.model.*

object DeckGenerator {
    /**
     * Generates a standard Mindikot deck. For 6 players, Twos are excluded.
     * @param numPlayers The number of players (2, 4 or 6).
     * @return An immutable, shuffled list of cards for the game.
     */
    fun generateDeck(numPlayers: Int): List<Card> { // Changed return type
        require(numPlayers == 4 || numPlayers == 6 || numPlayers == 2) { "Mindikot supports 2, 4 or 6 players only." }
        val includeTwos = (numPlayers == 4)

        val deck = mutableListOf<Card>() // Still use mutable locally for building
        val allSuits = Suit.values()
        val allRanks = Rank.values()

        for (suit in allSuits) {
            for (rank in allRanks) {
                // Skip Twos if playing with 6 players
                if (!includeTwos && rank == Rank.TWO) {
                    continue
                }
                deck.add(Card(suit, rank))
            }
        }
        // Shuffle the generated deck thoroughly before returning
        // shuffled() already returns a new List<Card>
        return deck.shuffled() // Return immutable List
    }
}
""",
    "core/engine/GameEngine.kt": """
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
""",
    "core/engine/RoundEvaluator.kt": """
package com.example.mindikot.core.engine

import com.example.mindikot.core.model.*
import com.example.mindikot.core.state.GameState

object RoundEvaluator {

    /**
     * Data class to hold the result of a round evaluation.
     * @property winningTeam The team that won the round (can be null if draw).
     * @property isKot True if the win was due to collecting all four Tens.
     */
    data class RoundResult(val winningTeam: Team?, val isKot: Boolean)

    /**
     * Evaluates the end of a round: checks for Kot, then majority of tens, then trick tie-breaker.
     * This function is pure and reads the immutable GameState.
     *
     * @param state The final GameState at the end of the round.
     * @return RoundResult indicating the winning team (or null for draw) and whether it was a Kot
     * win.
     */
    fun evaluateRound(state: GameState): RoundResult {
        val teams = state.teams // Immutable list
        println("Evaluating round end...") // Logging

        // 1. Instant Kot Check
        teams.find { it.hasKot() }?.let { winningTeam ->
            println("Team ${winningTeam.id} wins round by KOT!") // Logging
            return RoundResult(winningTeam, true)
        }

        // 2. Count Tens for each team (uses immutable teams/cards)
        val teamTensCount = teams.associate { team -> team.id to team.countTens() }
        println("Tens collected: $teamTensCount") // Logging

        // 3. Determine Team with Most Tens
        val maxTens = teamTensCount.values.maxOrNull() ?: 0
        val teamsWithMaxTensIds = teamTensCount.filterValues { it == maxTens }.keys

        // 4. Handle Winner Determination
        if (teamsWithMaxTensIds.size == 1) {
            // One team has clear majority of tens
            val winningTeamId = teamsWithMaxTensIds.first()
            val winningTeam = teams.first { it.id == winningTeamId } // Find team by ID
            println(
                    "Team ${winningTeam.id} wins round with majority of tens ($maxTens)."
            ) // Logging
            return RoundResult(winningTeam, false) // Not Kot
        } else if (teamsWithMaxTensIds.size > 1 && maxTens > 0) { // Only apply tie-breaker if tens were actually tied (>0)
            // Tie in tens - apply trick tie-breaker
            println("Tie in tens ($maxTens each). Applying trick tie-breaker.") // Logging
            val teamTricksWon = state.tricksWon // Get tricks won map from GameState (immutable)
            val tiedTeamsTrickCounts = teamTricksWon.filterKeys { it in teamsWithMaxTensIds }
            println("Tricks won by tied teams: $tiedTeamsTrickCounts") // Logging

            val maxTricks =
                    tiedTeamsTrickCounts.values.maxOrNull()
                            ?: -1 // Use -1 to detect no tricks won case

            // If maxTricks is 0 or less, it's still a draw (no one won tricks among tied teams)
            if (maxTricks <= 0) {
                 println("Round is a DRAW (tied tens, no decisive tricks won among tied teams).") // Logging
                 return RoundResult(null, false) // Indicate draw
            }


            val teamsWithMaxTricks = tiedTeamsTrickCounts.filterValues { it == maxTricks }.keys

            if (teamsWithMaxTricks.size == 1) {
                // One team won more tricks among the tied teams
                val winningTeamId = teamsWithMaxTricks.first()
                val winningTeam = teams.first { it.id == winningTeamId } // Find team by ID
                println(
                        "Team ${winningTeam.id} wins round due to trick tie-breaker ($maxTricks tricks)."
                ) // Logging
                return RoundResult(winningTeam, false) // Not Kot
            } else {
                // Still tied (same number of tens AND same number of tricks among those tied) -> Draw
                println("Round is a DRAW (tied tens and tricks among top teams).") // Logging
                return RoundResult(null, false) // Indicate draw
            }
        } else {
            // No tens collected by anyone, or some other edge case? Treat as draw.
            println(
                    "Round evaluation resulted in no winner (likely no tens collected or tie with 0 tens). Treating as Draw."
            ) // Logging
            return RoundResult(null, false)
        }
    }
}
""",
    "core/engine/TrickHandler.kt": """
package com.example.mindikot.core.engine

import com.example.mindikot.core.model.*

object TrickHandler {
    /**
     * Determines the winner of a completed trick based on Mindikot rules.
     * This function is pure and reads immutable data.
     *
     * @param playedCards A list of (Player, Card) pairs representing the cards played in the trick,
     * in order. Assumes Player objects within the pairs are consistent references.
     * @param trumpSuit The active trump suit for the round (can be null if trump is not set).
     * @return The Player object (reference from the input list) who won the trick.
     */
    fun determineTrickWinner(playedCards: List<Pair<Player, Card>>, trumpSuit: Suit?): Player {
        require(playedCards.isNotEmpty()) { "Cannot determine winner of an empty trick." }

        val leadSuit = playedCards.first().second.suit
        var winningPlay: Pair<Player, Card> // Will hold the reference to the winning Pair

        // Check for highest trump card first
        val trumpPlays = playedCards.filter { it.second.suit == trumpSuit && trumpSuit != null }
        if (trumpPlays.isNotEmpty()) {
            // Find the Pair with the highest ranking trump card
            winningPlay = trumpPlays.maxByOrNull { it.second.rank.value }!! // Non-null assertion safe due to isNotEmpty check
        } else {
            // No trump played, check for highest card of the lead suit
            val leadSuitPlays = playedCards.filter { it.second.suit == leadSuit }
            // We know leadSuitPlays is not empty because the leader played one.
            // Find the Pair with the highest ranking card of the lead suit
            winningPlay = leadSuitPlays.maxByOrNull { it.second.rank.value }!! // Non-null assertion safe as list is not empty
        }

        // Return the Player part of the winning Pair
        return winningPlay.first
    }
}
""",
    "core/engine/TrumpHandler.kt": """
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
""",
    # --- ui/viewmodel ---
    "ui/viewmodel/GameViewModel.kt": """
package com.example.mindikot.ui.viewmodel

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mindikot.core.engine.GameEngine
import com.example.mindikot.core.engine.RoundEvaluator
import com.example.mindikot.core.model.*
import com.example.mindikot.core.state.GameState
import com.example.mindikot.core.state.InputType
import com.example.mindikot.ui.viewmodel.network.NetworkMessage
import com.example.mindikot.ui.viewmodel.network.MessageType
import com.example.mindikot.ui.viewmodel.utils.log // Import utils
import com.example.mindikot.ui.viewmodel.utils.logError
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.BufferedReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

class GameViewModel(
    internal val applicationContext: Context
) : ViewModel() {

    // --- Game State (Immutable) ---
    val _state = MutableStateFlow(createInitialEmptyGameState())
    val state: StateFlow<GameState> = _state.asStateFlow()

    // --- Navigation/Events ---
    internal val _navigateToResultScreen = MutableSharedFlow<RoundEvaluator.RoundResult>(replay = 0, extraBufferCapacity = 1)
    val navigateToResultScreen: SharedFlow<RoundEvaluator.RoundResult> = _navigateToResultScreen.asSharedFlow()

    internal val _showError = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 5)
    val showError: SharedFlow<String> = _showError.asSharedFlow()

    // --- Game/Network Setup ---
    var isHost: Boolean = false
        private set
    var requiredPlayerCount: Int = 4
        private set
    var localPlayerId: Int = -1 // -1 indicates not assigned yet
        private set

    internal fun setLocalPlayerIdInternal(id: Int) {
        localPlayerId = id
        // If host, update own player name in initial state if needed (though usually done in initialize)
        if (isHost && id == 0) {
            val currentName = _state.value.players.getOrNull(0)?.name
             if (currentName != null && currentName == "Waiting...") {
                 // This path might be less common if initializeGameSettings handles it
                 // updatePlayerName(0, "Your Initial Name") // Replace with actual name source if available
             }
        }
    }


    internal val _connectedPlayersCount = MutableStateFlow(1)
    val connectedPlayersCount: StateFlow<Int> = _connectedPlayersCount.asStateFlow()

    internal val _gameStarted = MutableStateFlow(false)
    val gameStarted: StateFlow<Boolean> = _gameStarted.asStateFlow()

    internal fun setGameStartedInternal(started: Boolean) {
        _gameStarted.value = started
    }

    // --- Networking (Host - Properties managed here, logic in extensions) ---
    internal var serverSocket: ServerSocket? = null
    internal var servicePort: Int = 0
        private set
    internal fun setServicePortInternal(port: Int) { servicePort = port }

    @Volatile internal var isServerRunning: Boolean = false
    internal val clientSockets = ConcurrentHashMap<Int, Socket>()
    internal val clientWriters = ConcurrentHashMap<Int, PrintWriter>()
    internal val clientReaders = ConcurrentHashMap<Int, BufferedReader>()
    internal val clientJobs = ConcurrentHashMap<Int, Job>()

    // --- Networking (Client - Properties managed here, logic in extensions) ---
    internal var clientSocket: Socket? = null
    internal var clientWriter: PrintWriter? = null
    internal var clientReader: BufferedReader? = null
    internal var clientReaderJob: Job? = null
    @Volatile internal var isConnectedToServer: Boolean = false
        internal set

    // --- NSD (Properties managed here, logic in extensions) ---
    internal var nsdManager: NsdManager? = null
    internal var registrationListener: NsdManager.RegistrationListener? = null
    internal var discoveryListener: NsdManager.DiscoveryListener? = null
    internal var nsdServiceNameRegistered: String? = null
        private set
    internal fun setNsdServiceNameRegisteredInternal(name: String?) { nsdServiceNameRegistered = name }

    internal val _discoveredHosts = MutableStateFlow<List<NsdServiceInfo>>(emptyList())
    val discoveredHosts: StateFlow<List<NsdServiceInfo>> = _discoveredHosts.asStateFlow()
    internal val _hostIpAddress = MutableStateFlow<String?>(null)
    val hostIpAddress: StateFlow<String?> = _hostIpAddress.asStateFlow()
    internal val resolvingServices = ConcurrentHashMap<String, Boolean>()

    // --- Serialization ---
    internal val gson = Gson()

    // ========================================================================
    // INITIALIZATION & SETUP
    // ========================================================================
    fun initializeGameSettings(
        playerName: String,
        mode: GameMode,
        host: Boolean = true,
        playersNeeded: Int = 4
    ) {
        log("Initializing game settings. Host: $host, Name: $playerName, Mode: $mode, Players: $playersNeeded")
        isHost = host
        requiredPlayerCount = playersNeeded
        localPlayerId = if (host) 0 else -1 // Host is always 0, client waits for assignment

        // Create initial immutable players list
        val players = List(playersNeeded) { i -> // Use List constructor
            Player(
                id = i,
                name = if (i == 0 && host) playerName else "Waiting...", // Host sets own name directly
                teamId = (i % 2) + 1, // Simple team assignment (1, 2, 1, 2...)
                hand = emptyList() // Start with empty immutable hand
            )
        }
        // Create initial immutable teams list
        val teams = listOf(
            Team(id = 1, players = players.filter { it.teamId == 1 }, collectedCards = emptyList()),
            Team(id = 2, players = players.filter { it.teamId == 2 }, collectedCards = emptyList())
        )

        // Reset state with new immutable GameState
        _state.value = GameState(
            players = players,
            teams = teams,
            gameMode = mode,
            tricksWon = mapOf(1 to 0, 2 to 0), // Initialize trick counts with immutable map
            currentLeaderIndex = 0,
            trumpSuit = null,
            trumpRevealed = false,
            hiddenCard = null,
            currentTrickPlays = emptyList(), // Use empty immutable list
            awaitingInputFromPlayerIndex = null,
            requiredInputType = null
        )
        _connectedPlayersCount.value = if (host) 1 else 0
        _gameStarted.value = false
        log("Initial GameState created for setup. isHost=$isHost, localPlayerId=$localPlayerId. State: ${_state.value}")

         if (!isHost) {
            _discoveredHosts.value = emptyList()
         }
    }

    /** HOST: Updates player name in the authoritative state (immutably) and broadcasts the change */
    internal fun updatePlayerName(playerId: Int, name: String) {
        if (!isHost) return
        if (name.isBlank()) {
             logError("Attempted to update Player $playerId name to blank. Ignoring.")
             return
        }

        log("Host: Updating Player $playerId name to '$name'")

        val currentState = _state.value
        val playerIndex = currentState.players.indexOfFirst { it.id == playerId }

        if (playerIndex != -1) {
            val currentPlayer = currentState.players[playerIndex]
            // Update only if different and not placeholder/disconnect messages
            if (currentPlayer.name != name && currentPlayer.name != "[Disconnected]" && !currentPlayer.name.contains("[LEFT]")) {
                 // Create new player object with updated name
                 val updatedPlayer = currentPlayer.copy(name = name)
                 // Create new players list
                 val updatedPlayers = currentState.players.toMutableList().apply {
                     set(playerIndex, updatedPlayer)
                 }.toList() // Convert back to immutable List
                 // Create new game state
                 val newState = currentState.copy(players = updatedPlayers)
                 // Update the StateFlow
                 _state.value = newState
                 // Broadcast the updated state
                 broadcastGameState(newState) // Use host extension method
                 log("Host: Player $playerId name updated. New state pushed.")
            } else {
                 log("Host: Player $playerId name update skipped (name same or placeholder).")
            }
        } else {
            logError("Host: Cannot update name, Player $playerId not found in current state.")
        }
    }


    // ========================================================================
    // GAME LOGIC PROCESSING (HOST ONLY - Uses Immutable Engine)
    // ========================================================================
    /** HOST ONLY: Processes validated player input using the immutable GameEngine and broadcasts the result */
    internal fun processGameInput(actingPlayerId: Int, playerInput: Any) {
        if (!isHost) {
            logError("processGameInput called on client device. Ignoring.")
            return
        }

        viewModelScope.launch(Dispatchers.Main.immediate) { // Ensure updates on Main thread
            val currentState = _state.value // Get current immutable state

            // --- Pre-condition Checks ---
            if (currentState.awaitingInputFromPlayerIndex != actingPlayerId) {
                 logError("Host: Input received from Player $actingPlayerId, but expected input from ${currentState.awaitingInputFromPlayerIndex}")
                 // Send an error message to the acting player? Or just ignore.
                 // sendMessageToClient(actingPlayerId, NetworkMessage(MessageType.ERROR, "Not your turn or unexpected action."))
                 return@launch
            }
            if (currentState.requiredInputType == null) {
                 logError("Host: Input received from Player $actingPlayerId, but no input was currently required.")
                 // sendMessageToClient(actingPlayerId, NetworkMessage(MessageType.ERROR, "No action expected from you right now."))
                return@launch
            }

            log("Host: Processing input from Player $actingPlayerId ($playerInput), required: ${currentState.requiredInputType}")

            // --- Process with Immutable GameEngine ---
            val newState: GameState
            try {
                 // GameEngine.processPlayerInput returns a NEW state instance or throws IllegalStateException
                 newState = GameEngine.processPlayerInput(currentState, playerInput)

            } catch (e: IllegalStateException) {
                logError("Host: Invalid move or state error processing input for Player $actingPlayerId: ${e.message}")
                sendMessageToClient(actingPlayerId, NetworkMessage(MessageType.ERROR, "Invalid Move: ${e.message}"))
                // Re-request input from the *same* player using the *current* (unchanged) state.
                val requestedState = GameEngine.requestInput(currentState, actingPlayerId)
                // Update state flow ONLY LOCALLY FOR HOST to reflect the re-request
                _state.value = requestedState
                // Broadcast the state requiring re-input to ALL clients
                broadcastGameState(requestedState)
                log("Host: Invalid move by $actingPlayerId. Re-requesting input. Broadcasted state.")
                return@launch // Stop further processing after error

            } catch (e: Exception) { // Catch unexpected errors during processing
                 logError("Host: Unexpected error processing game input for Player $actingPlayerId", e)
                 sendMessageToClient(actingPlayerId, NetworkMessage(MessageType.ERROR, "Internal server error during your turn."))
                 // Attempt to re-request input from the same player with current state as a recovery mechanism
                 val requestedState = GameEngine.requestInput(currentState, actingPlayerId)
                 // Update state flow LOCALLY for host
                 _state.value = requestedState
                 // Broadcast the state requiring re-input to ALL clients
                 broadcastGameState(requestedState)
                 logError("Host: Unexpected error processing input for $actingPlayerId. Re-requesting input. Broadcasted state.", e)
                 return@launch // Stop further processing after error
            }

            // --- Successful Processing: Update State ---
            // newState is the successfully processed, new immutable state.
             _state.value = newState // Assign the NEW state directly to the StateFlow

            log("Host: Input processed successfully. State updated locally. New State Awaiting: ${newState.awaitingInputFromPlayerIndex} Type: ${newState.requiredInputType}")

            // --- Check for Round End (using the new state) ---
            val roundEnded = newState.players.firstOrNull()?.hand?.isEmpty() == true && newState.currentTrickPlays.isEmpty()

            if (roundEnded) {
                log("Host: Round Ended. Evaluating final state...")
                 val result = RoundEvaluator.evaluateRound(newState) // Evaluate the final state
                 log("Host: Round Result: Winner=${result.winningTeam?.id ?: "Draw"}, Kot=${result.isKot}")

                 // Broadcast the final round state *after* local state update
                 broadcastGameState(newState)

                 // Navigate after a short delay to allow UI update from final state broadcast
                 delay(500) // Slightly increased delay
                 log("Host: Emitting navigation to result screen.")
                 _navigateToResultScreen.tryEmit(result) // Emit navigation event

            } else {
                 // Round not ended, broadcast the successfully updated state
                 broadcastGameState(newState)
                 log("Host: Broadcasted updated game state.")
            }
        }
    }

    // ========================================================================
    // UI ACTION HANDLERS (Client-side validation and sending)
    // ========================================================================
    fun onCardPlayed(card: Card) {
         val localId = localPlayerId
         if (localId == -1) {
              viewModelScope.launch { _showError.emit("Cannot play: Player ID not assigned.") }
              return
         }
        log("UI Action: Card played: $card by Local Player $localId")
        val currentState = _state.value // Read current state
        val myTurn = currentState.awaitingInputFromPlayerIndex == localId
        val expectedInput = currentState.requiredInputType

        // Basic client-side checks
        if (!myTurn) {
            logError("UI Action: Card played, but not player $localId's turn.")
            viewModelScope.launch { _showError.emit("Not your turn!") }
            return
        }
        // Allow playing card if expected type is PLAY_CARD or CHOOSE_TRUMP_SUIT
        val validExpectation = expectedInput == InputType.PLAY_CARD || expectedInput == InputType.CHOOSE_TRUMP_SUIT
        if (!validExpectation) {
            logError("UI Action: Card played, but expected input was $expectedInput.")
            viewModelScope.launch { _showError.emit("Cannot play card now (Expected: $expectedInput).") }
            return
        }

        val localPlayer = currentState.players.find { it.id == localId }
        if (localPlayer == null || !localPlayer.hand.contains(card)) {
             logError("UI Action: Card $card not found in local player's hand: ${localPlayer?.hand}")
            viewModelScope.launch { _showError.emit("Card not in hand!") }
            return
        }

        // Client-side valid move check (using immutable engine function)
        val validMoves = GameEngine.determineValidMoves(
            playerHand = localPlayer.hand,
            currentTrickPlays = currentState.currentTrickPlays,
            trumpSuit = currentState.trumpSuit,
            trumpRevealed = currentState.trumpRevealed
        )
        if (!validMoves.contains(card)) {
             // Additional check for Mode B post-reveal constraint (client-side approximation)
            val justRevealed = currentState.requiredInputType == InputType.PLAY_CARD &&
                                currentState.trumpRevealed &&
                                currentState.gameMode == GameMode.FIRST_CARD_HIDDEN
                                // This approximation assumes if trump is revealed and PLAY_CARD is expected,
                                // the player *might* have just revealed. Server MUST verify definitively.

            val mustPlayTrump = justRevealed &&
                                currentState.trumpSuit != null &&
                                localPlayer.hand.any { it.suit == currentState.trumpSuit }

            if (mustPlayTrump && card.suit != currentState.trumpSuit) {
                 logError("UI Action: Invalid card $card played after reveal (client check). Must play trump ${currentState.trumpSuit}. Valid: $validMoves")
                 viewModelScope.launch { _showError.emit("Invalid move (Must play trump after revealing).") }
                 return
            } else if (!validMoves.contains(card)) {
                // General rule violation (e.g. not following suit)
                logError("UI Action: Invalid card $card played based on client-side check. Valid: $validMoves")
                viewModelScope.launch { _showError.emit("Invalid move (Rule violation).") }
                return
            }
        }


        // Process or Send
        if (isHost) {
            processGameInput(localId, card)
        } else {
            sendMessageToServer(NetworkMessage(MessageType.PLAYER_ACTION, card))
            // Consider optimistic UI update here if desired (e.g., gray out card)
        }
    }

    fun onRevealOrPass(decision: GameEngine.Decision) {
         val localId = localPlayerId
         if (localId == -1) {
              viewModelScope.launch { _showError.emit("Cannot act: Player ID not assigned.") }
              return
         }
        log("UI Action: Reveal/Pass decision: $decision by Local Player $localId")
        val currentState = _state.value

        // Basic checks
        if (currentState.awaitingInputFromPlayerIndex != localId) {
            logError("UI Action: Reveal/Pass action, but not player $localId's turn.")
            viewModelScope.launch { _showError.emit("Not your turn!") }
            return
        }
        if (currentState.requiredInputType != InputType.REVEAL_OR_PASS) {
            logError("UI Action: Reveal/Pass action, but expected input was ${currentState.requiredInputType}.")
            viewModelScope.launch { _showError.emit("Cannot Reveal or Pass now (Expected: ${currentState.requiredInputType}).") }
            return
        }
         if (currentState.gameMode != GameMode.FIRST_CARD_HIDDEN) {
              logError("UI Action: Reveal/Pass action, but game mode is ${currentState.gameMode}.")
             viewModelScope.launch { _showError.emit("Reveal/Pass is only available in FIRST_CARD_HIDDEN mode.") }
             return
         }


        // Process or Send
        if (isHost) {
            processGameInput(localId, decision)
        } else {
            sendMessageToServer(NetworkMessage(MessageType.PLAYER_ACTION, decision))
            // Client waits for server state update
        }
    }

    // ========================================================================
    // UTILITY & LIFECYCLE
    // ========================================================================
    /** Creates an empty initial game state using immutable structures */
    internal fun createInitialEmptyGameState(): GameState {
        return GameState(
            players = emptyList(),
            teams = emptyList(),
            gameMode = GameMode.CHOOSE_WHEN_EMPTY, // Default mode
            currentLeaderIndex = 0,
            trumpSuit = null,
            trumpRevealed = false,
            hiddenCard = null,
            currentTrickPlays = emptyList(), // Use immutable empty list
            awaitingInputFromPlayerIndex = null,
            requiredInputType = null,
            tricksWon = emptyMap() // Use immutable empty map
        )
    }

    override fun onCleared() {
        log("GameViewModel Cleared. Cleaning up resources...")
        viewModelScope.launch { // Launch cleanup in a coroutine for suspension points
            if (isHost) {
                 stopServerAndDiscovery() // Suspending function likely
            } else {
                stopNsdDiscovery() // Suspending function likely
                disconnectFromServer() // Suspending function likely
            }
            log("GameViewModel Network Cleanup Complete.")
        }
        // Note: viewModelScope is automatically cancelled after onCleared returns,
        // so the launched coroutine will be cancelled if still running.
        super.onCleared()
        log("GameViewModel onCleared Finished.")
    }

  }
"""
}

# --- Python Script to Write Files ---

# Loop through the dictionary and write each file
print("Starting file writing process...")
for file_path_str, updated_content in updated_files_content.items():
    # Create Path object relative to the base directory
    path = base_dir / Path(file_path_str.replace("/", os.sep)) # Use os.sep for cross-platform compatibility
    print(f"Processing: {path}")

    # Ensure parent directory exists
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
    except Exception as e:
        print(f"[ERROR] Could not create directory {path.parent}: {e}")
        continue # Skip to next file

    # Write the file content
    try:
        with open(path, 'w', encoding='utf-8') as f:
            # Strip leading/trailing whitespace from content for cleaner files
            f.write(updated_content.strip())
        print(f"  Successfully wrote {path.name}")
    except IOError as e:
        print(f"[ERROR] Could not write file {path}: {e}")
    except Exception as e:
        print(f"[ERROR] An unexpected error occurred while writing {path}: {e}")

print("\nFile writing process completed.")