from pathlib import Path

# base_dir = Path("/mnt/data/mindikot/src/main/java/com/example/mindikot/core")
base_dir = Path("src/main/java/com/example/mindikot/core")

# All file paths relative to base_dir with updated content
updated_files_content = {
    "model/Card.kt": """
package com.example.mindikot.core.model

data class Card(val suit: Suit, val rank: Rank) {
    override fun toString(): String = "${'$'}rank of ${'$'}suit"
}
""",
    "model/Suit.kt": """
package com.example.mindikot.core.model

enum class Suit {
    HEARTS, DIAMONDS, CLUBS, SPADES
}
""",
    "model/Rank.kt": """
package com.example.mindikot.core.model

enum class Rank(val value: Int) {
    THREE(3), FOUR(4), FIVE(5), SIX(6), SEVEN(7), EIGHT(8), NINE(9),
    TEN(10), JACK(11), QUEEN(12), KING(13), ACE(14)
}
""",
    "model/Player.kt": """
package com.example.mindikot.core.model

data class Player(val id: Int, val name: String, val teamId: Int, val hand: MutableList<Card> = mutableListOf()) {
    override fun toString() = "Player(id=${'$'}id, name=${'$'}name, team=${'$'}teamId)"
}
""",
    "model/Team.kt": """
package com.example.mindikot.core.model

data class Team(val id: Int, val players: List<Player>, val collectedCards: MutableList<Card> = mutableListOf()) {
    fun countTens(): Int = collectedCards.count { it.rank == Rank.TEN }
    fun hasKot(): Boolean = countTens() == 4
}
""",
    "model/GameMode.kt": """
package com.example.mindikot.core.model

enum class GameMode {
    CHOOSE_WHEN_EMPTY,
    FIRST_CARD_HIDDEN
}
""",
    "engine/DeckGenerator.kt": """
package com.example.mindikot.core.engine

import com.example.mindikot.core.model.*

object DeckGenerator {
    fun generateDeck(includeTwos: Boolean): MutableList<Card> {
        val deck = mutableListOf<Card>()
        for (suit in Suit.values()) {
            for (rank in Rank.values()) {
                if (!includeTwos && rank == Rank.THREE) continue
                deck.add(Card(suit, rank))
            }
        }
        return deck.shuffled().toMutableList()
    }
}
""",
    "engine/TrickHandler.kt": """
package com.example.mindikot.core.engine

import com.example.mindikot.core.model.*

object TrickHandler {
    fun determineTrickWinner(playedCards: List<Pair<Player, Card>>, trumpSuit: Suit?): Player {
        val leadSuit = playedCards.first().second.suit
        val validCards = playedCards.map { (player, card) ->
            val score = when {
                card.suit == trumpSuit -> card.rank.value + 100
                card.suit == leadSuit -> card.rank.value
                else -> 0
            }
            Triple(player, card, score)
        }
        return validCards.maxBy { it.third }.first
    }
}
""",
    "engine/TrumpHandler.kt": """
package com.example.mindikot.core.engine

import com.example.mindikot.core.model.*

object TrumpHandler {
    fun chooseTrumpFromHand(player: Player): Suit {
        return player.hand.groupingBy { it.suit }.eachCount().maxBy { it.value }.key
    }
}
""",
    "engine/RoundEvaluator.kt": """
package com.example.mindikot.core.engine

import com.example.mindikot.core.model.*

object RoundEvaluator {
    data class RoundResult(val winningTeam: Team, val isKot: Boolean)

    fun evaluateRound(teams: List<Team>): RoundResult {
        val teamWithKot = teams.find { it.hasKot() }
        return if (teamWithKot != null) {
            RoundResult(teamWithKot, true)
        } else {
            val team = teams.maxBy { it.countTens() }
            RoundResult(team, false)
        }
    }
}
""",
    "engine/GameEngine.kt": """
package com.example.mindikot.core.engine

import com.example.mindikot.core.model.*
import com.example.mindikot.core.state.GameState

object GameEngine {
    fun playTrick(state: GameState, leaderIndex: Int): Int {
        val trickCards = mutableListOf<Pair<Player, Card>>()
        val players = state.players
        val trumpSuit = state.trumpSuit

        for (i in 0 until players.size) {
            val currentPlayer = players[(leaderIndex + i) % players.size]
            val playableCard = currentPlayer.hand.removeAt(0)
            trickCards.add(currentPlayer to playableCard)
        }

        val winner = TrickHandler.determineTrickWinner(trickCards, trumpSuit)
        val team = state.teams.first { it.id == winner.teamId }
        team.collectedCards.addAll(trickCards.map { it.second })

        return players.indexOf(winner)
    }
}
""",
    "state/GameState.kt": """
package com.example.mindikot.core.state

import com.example.mindikot.core.model.*

data class GameState(
    val players: List<Player>,
    val teams: List<Team>,
    val gameMode: GameMode,
    var trumpSuit: Suit? = null,
    var hiddenTrumpCard: Card? = null
)
"""
}

# Write each updated file
for relative_path, content in updated_files_content.items():
    path = base_dir / relative_path
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content.strip())

"✅ All specified files were created/updated with actual logic."
