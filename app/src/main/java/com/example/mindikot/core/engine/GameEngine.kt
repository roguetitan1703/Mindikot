package com.example.mindikot.core.engine

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
