package com.example.mindikot.core.state

import com.example.mindikot.core.model.*

/**
 * Represents the complete state of the Mindikot game at any point in time. This object should be
 * immutable or handled carefully to ensure state consistency, especially when shared across network
 * or threads. Consider using immutable data structures if concurrency becomes complex.
 *
 * @property players List of all players participating in the game.
 * @property teams List of the teams, typically containing references to their players and collected
 * cards.
 * @property gameMode The selected mode for trump determination (e.g., CHOOSE_WHEN_EMPTY,
 * FIRST_CARD_HIDDEN).
 * @property currentLeaderIndex The index (in the `players` list) of the player who leads the
 * current or next trick.
 * @property trumpSuit The suit designated as trump for the current round. Null if trump has not
 * been set yet.
 * @property trumpRevealed Boolean flag indicating whether the trump suit has been determined and
 * revealed for the current round.
 * @property hiddenCard In FIRST_CARD_HIDDEN mode, this holds the card set aside *before dealing*.
 * Null otherwise.
 * @property currentTrickPlays The cards played so far in the trick-in-progress. List<Pair<Player,
 * Card>>.
 * @property awaitingInputFromPlayerIndex Index of the player from whom input is currently awaited.
 * Null if the engine is processing or trick/round ended.
 * @property requiredInputType The type of input needed from the awaiting player.
 * @property tricksWon A map storing the number of tricks won by each team (TeamId -> Trick Count)
 * in the current round. Used for tie-breaking.
 */
data class GameState(
        val players: List<Player>,
        val teams: List<Team>,
        val gameMode: GameMode,
        var currentLeaderIndex: Int = 0,
        var trumpSuit: Suit? = null,
        var trumpRevealed: Boolean = false,
        var hiddenCard: Card? = null, // Card set aside BEFORE dealing in FIRST_CARD_HIDDEN mode
        val currentTrickPlays: MutableList<Pair<Player, Card>> =
                mutableListOf(), // State of the current trick
        var awaitingInputFromPlayerIndex: Int? = null, // Which player needs to act
        var requiredInputType: InputType? = null, // What kind of action is needed
        val tricksWon: MutableMap<Int, Int> =
                mutableMapOf() // TeamId -> Trick Count for current round
)

/** Enum to represent the type of input currently required from a player. */
enum class InputType {
    PLAY_CARD, // Player needs to select a card to play
    CHOOSE_TRUMP_SUIT, // Player needs to choose a trump suit by playing a card (Mode A)
    REVEAL_OR_PASS // Player needs to decide Reveal or Pass (Mode B)
}
