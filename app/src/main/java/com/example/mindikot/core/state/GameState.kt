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
    val tricksWon: Map<Int, Int> = emptyMap(),      // Immutable Map
    val lastTrick: List<Pair<Player, Card>> = emptyList(), // Immutable List
)

/** Enum to represent the type of input currently required from a player. */
enum class InputType {
    PLAY_CARD,
    CHOOSE_TRUMP_SUIT,
    REVEAL_OR_PASS
}