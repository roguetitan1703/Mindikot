from pathlib import Path

# Base directory for Kotlin source files
base_dir = Path("src/main/java/com/example/mindikot/core")

# File contents mapping
file_contents = {
    base_dir / "model" / "Card.kt": '''package com.example.mindikot.core.model

data class Card(val suit: Suit, val rank: Rank) {
    override fun toString(): String = "${'$'}rank of ${'$'}suit"
}
''',
    base_dir / "model" / "Suit.kt": '''package com.example.mindikot.core.model

enum class Suit {
    CLUBS, DIAMONDS, HEARTS, SPADES
}
''',
    base_dir / "model" / "Rank.kt": '''package com.example.mindikot.core.model

enum class Rank(val value: Int) {
    TWO(2), THREE(3), FOUR(4), FIVE(5), SIX(6),
    SEVEN(7), EIGHT(8), NINE(9), TEN(10),
    JACK(11), QUEEN(12), KING(13), ACE(14)
}
''',
    base_dir / "model" / "Player.kt": '''package com.example.mindikot.core.model

data class Player(
    val id: Int,
    val name: String,
    val isBot: Boolean = false,
    val teamId: Int,
    val hand: MutableList<Card> = mutableListOf()
)
''',
    base_dir / "model" / "Team.kt": '''package com.example.mindikot.core.model

data class Team(
    val id: Int,
    val players: List<Player>,
    var score: Int = 0,
    val capturedCards: MutableList<Card> = mutableListOf()
)
''',
    base_dir / "model" / "GameMode.kt": '''package com.example.mindikot.core.model

enum class GameMode {
    CHOOSE_WHEN_EMPTY,
    FIRST_CARD_HIDDEN
}
''',
    base_dir / "engine" / "DeckGenerator.kt": '''package com.example.mindikot.core.engine

import com.example.mindikot.core.model.Card
import com.example.mindikot.core.model.Rank
import com.example.mindikot.core.model.Suit
import com.example.mindikot.core.model.Player

object DeckGenerator {
    fun generateDeck(includeTwos: Boolean = true): MutableList<Card> {
        val deck = mutableListOf<Card>()
        for (suit in Suit.values()) {
            for (rank in Rank.values()) {
                if (!includeTwos && rank == Rank.TWO) continue
                deck.add(Card(suit, rank))
            }
        }
        deck.shuffle()
        return deck
    }

    fun dealCards(players: List<Player>, deck: MutableList<Card>) {
        val handSize = deck.size / players.size
        players.forEach { player ->
            repeat(handSize) {
                deck.removeFirstOrNull()?.let(player.hand::add)
            }
        }
    }
}
''',
    base_dir / "engine" / "TrickHandler.kt": '''package com.example.mindikot.core.engine

import com.example.mindikot.core.model.Card
import com.example.mindikot.core.model.Player
import com.example.mindikot.core.state.GameState

object TrickHandler {
    fun determineTrickWinner(played: List<Pair<Player, Card>>, state: GameState): Player {
        val trump = state.trumpSuit
        val trumpPlays = played.filter { it.second.suit == trump }
        val contenders = if (trump != null && trumpPlays.isNotEmpty()) {
            trumpPlays
        } else {
            val leadSuit = played.first().second.suit
            played.filter { it.second.suit == leadSuit }
        }
        return contenders.maxByOrNull { it.second.rank.value }!!.first
    }
}
''',
    base_dir / "engine" / "TrumpHandler.kt": '''package com.example.mindikot.core.engine

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
''',
    base_dir / "engine" / "RoundEvaluator.kt": '''package com.example.mindikot.core.engine

import com.example.mindikot.core.model.Rank
import com.example.mindikot.core.model.Team

data class RoundResult(
    val winningTeam: Team,
    val points: Int,
    val isKot: Boolean
)

object RoundEvaluator {
    fun evaluateRound(teams: List<Team>): RoundResult {
        val tensCount = teams.associateWith { team ->
            team.capturedCards.count { it.rank == Rank.TEN }
        }
        val (winningTeam, count) = tensCount.maxByOrNull { it.value }!!
        val isKot = count == 4
        val points = 1
        winningTeam.score += points
        return RoundResult(winningTeam, points, isKot)
    }
}
''',
    base_dir / "engine" / "GameEngine.kt": '''package com.example.mindikot.core.engine

import com.example.mindikot.core.model.Player
import com.example.mindikot.core.model.Card
import com.example.mindikot.core.model.Suit
import com.example.mindikot.core.model.GameMode
import com.example.mindikot.core.model.Team
import com.example.mindikot.core.state.GameState

class GameEngine(
    private val players: List<Player>,
    private val teams: List<Team>,
    private val gameMode: GameMode
) {
    val state = GameState(players, teams, gameMode)

    fun startNewRound(includeTwos: Boolean) {
        players.forEach { it.hand.clear() }
        teams.forEach { it.capturedCards.clear() }

        val deck = DeckGenerator.generateDeck(includeTwos)
        DeckGenerator.dealCards(players, deck)

        state.currentLeaderIndex = 0
        state.trumpSuit = null
        state.trumpRevealed = false
        state.hiddenCard = null
    }

    fun playTrick(played: List<Pair<Player, Card>>): Player {
        val winner = TrickHandler.determineTrickWinner(played, state)
        collectTrick(played, winner)
        return winner
    }

    private fun collectTrick(played: List<Pair<Player, Card>>, winner: Player) {
        teams.first { it.id == winner.teamId }.capturedCards.addAll(played.map { it.second })
        state.currentLeaderIndex = players.indexOf(winner)
    }

    fun handleTrump(player: Player, chosenSuit: Suit? = null, passDiscard: Card? = null) {
        TrumpHandler.handleTrumpSelection(state, player, chosenSuit, passDiscard)
    }

    fun endRound(): RoundResult {
        return RoundEvaluator.evaluateRound(teams)
    }
}
''',
    base_dir / "state" / "GameState.kt": '''package com.example.mindikot.core.state

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
'''
}

# Write files
for path, content in file_contents.items():
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, 'w') as f:
        f.write(content)

# Return list of created files
list(map(str, file_contents.keys()))
