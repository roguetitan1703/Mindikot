package com.example.mindikot.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mindikot.core.model.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import com.example.mindikot.core.state.GameState
class GameViewModel : ViewModel() {

    private val _state = MutableStateFlow(generateInitialGameState())
    val state: StateFlow<GameState> = _state.asStateFlow()

    private val _navigateToResultScreen = MutableSharedFlow<Unit>()
    val navigateToResultScreen: SharedFlow<Unit> = _navigateToResultScreen.asSharedFlow()

    fun selectTrump(suit: Suit) {
        _state.update {
            it.copy(trumpSuit = suit, trumpRevealed = true)
        }
    }

    fun playTrick() {
        val newPlayers = state.value.players.map { player ->
            if (player.hand.isNotEmpty()) {
                player.copy(hand = player.hand.drop(1).toMutableList())
            } else player
        }

        val updatedTeams = state.value.teams.map { team ->
            val cardsGained = (1..2).map {
                Card(Suit.values().random(), Rank.values().random())
            }.toMutableList()

            team.copy(collectedCards = (team.collectedCards + cardsGained).toMutableList())
        }

        _state.update {
            it.copy(players = newPlayers, teams = updatedTeams)
        }

        if (newPlayers.all { it.hand.isEmpty() }) {
            viewModelScope.launch {
                _navigateToResultScreen.emit(Unit)
            }
        }
    }

    fun restartGame() {
        _state.value = generateInitialGameState()
    }

    private fun generateInitialGameState(): GameState {
        val deck = generateDeck().shuffled()
        val players = List(4) { index ->
            val teamId = if (index % 2 == 0) 1 else 2  // Assign teamId based on player position
            Player(
                id = index,
                name = "Player ${index + 1}",
                teamId = teamId,  // Pass teamId here
                hand = deck.drop(index * 5).take(5).toMutableList()
            )
        }

        val teams = listOf(
            Team(id = 1, players = listOf(players[0], players[2])),
            Team(id = 2, players = listOf(players[1], players[3]))
        )

        return GameState(
            players = players,
            teams = teams,
            gameMode = GameMode.FIRST_CARD_HIDDEN,  // Default game mode
            trumpSuit = null,
            trumpRevealed = false
        )
    }
    fun changeGameMode(newMode: GameMode) {
        _state.update {
            it.copy(gameMode = newMode)
        }
    }

    fun setupGame(playerName: String, mode: GameMode, host: Boolean = false, playersNeeded: Int = 4) {
        isHost = host
        requiredPlayerCount = playersNeeded

        val deck = generateDeck().shuffled()
        val player = Player(
            id = 0,
            name = playerName,
            teamId = 0,
            hand = deck.take(5).toMutableList()
        )

        val teams = listOf(
            Team(id = 1, players = listOf(player)),
            Team(id = 2, players = listOf()) // placeholder
        )

        _state.value = GameState(
            players = listOf(player),
            teams = teams,
            gameMode = mode
        )
    }

    private fun generateDeck(): List<Card> {
        return Suit.entries.flatMap { suit ->
            Rank.entries.map { rank -> Card(suit, rank) }
        }
    }

    // Host/Join role
    var isHost: Boolean = false
        private set

    // Number of players required for this game
    var requiredPlayerCount: Int = 4
        private set

    // Game start trigger
    private val _gameStarted = MutableStateFlow(false)
    val gameStarted: StateFlow<Boolean> = _gameStarted
    fun startGame() {
        _gameStarted.value = true
    }
    fun setPlayerName(name: String) {
        _state.update { state ->
            state.copy(
                players = state.players.mapIndexed { index, player ->
                    if (index == 0) player.copy(name = name) else player
                }
            )
        }
    }


}
