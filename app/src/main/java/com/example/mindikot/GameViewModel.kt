package com.example.mindikot.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mindikot.core.model.*
import com.example.mindikot.core.state.GameState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket

class GameViewModel : ViewModel() {
    private fun logGameState(tag: String = "GameState") {
        val gameState = _state.value
        println("[$tag] Players:")
        gameState.players.forEach { player ->
            println("  - ${player.name} (ID: ${player.id}, Team: ${player.teamId}, Hand: ${player.hand})")
        }
        println("[$tag] Teams:")
        gameState.teams.forEach { team ->
            println("  - Team ${team.id}: ${team.players.joinToString { it.name }}")
        }
        println("[$tag] Mode: ${gameState.gameMode}")
        println("[$tag] Trump Suit: ${gameState.trumpSuit ?: "Not chosen"}")
        println("[$tag] Trump Revealed: ${gameState.trumpRevealed}")
    }

    private val _state = MutableStateFlow(generateInitialGameState())
    val state: StateFlow<GameState> = _state.asStateFlow()

    private val _navigateToResultScreen = MutableSharedFlow<Unit>()
    val navigateToResultScreen: SharedFlow<Unit> = _navigateToResultScreen.asSharedFlow()

    var isHost: Boolean = false
        private set

    var requiredPlayerCount: Int = 4
        private set
    var me: String = ""

    private val _gameStarted = MutableStateFlow(false)
    val gameStarted: StateFlow<Boolean> = _gameStarted

    private var serverSocket: ServerSocket? = null
    private var isServerRunning = false

    // Set up the game for the host with a given number of players and a mode.
    fun setupGame(playerName: String, mode: GameMode, host: Boolean = false, playersNeeded: Int = 4) {
        isHost = host
        requiredPlayerCount = playersNeeded

        val deck = generateDeck().shuffled()
        val player = Player(
            id = 0,
            name = playerName,
            teamId = 1,  // Player 1 will always start on Team 1
            hand = deck.take(5).toMutableList()
        )

        val players = mutableListOf(player)
        
        // Create other players (Initially empty hands for others)
        for (i in 1 until playersNeeded) {
            players.add(
                Player(
                    id = i,
                    name = "Player ${i + 1}",
                    teamId = if (i % 2 == 0) 1 else 2, // Alternate teams
                    hand = mutableListOf()
                )
            )
        }

        val teams = listOf(
            Team(id = 1, players = players.filter { it.teamId == 1 }),
            Team(id = 2, players = players.filter { it.teamId == 2 })
        )
        _state.value = GameState(
            players = players,
            teams = teams,
            gameMode = mode
        )
        logGameState("After setupGame")
    }

    // Change a player's team to either 1 or 2, reassign the teams dynamically.
    fun changePlayerTeam(playerId: Int, newTeamId: Int) {
        if (newTeamId != 1 && newTeamId != 2) return

        val updatedPlayers = _state.value.players.map { player ->
            if (player.id == playerId) {
                player.copy(teamId = newTeamId)
            } else player
        }.sortedBy { if (it.name == me) Int.MIN_VALUE else it.id }

        val team1Players = updatedPlayers.filter { it.teamId == 1 }
        val team2Players = updatedPlayers.filter { it.teamId == 2 }

        val balancedTeams = if (requiredPlayerCount == 4) {
            listOf(
                Team(id = 1, players = team1Players.take(2)),
                Team(id = 2, players = team2Players.take(2))
            )
        } else {
            listOf(
                Team(id = 1, players = team1Players.take(3)),
                Team(id = 2, players = team2Players.take(3))
            )
        }

        _state.update {
            it.copy(players = updatedPlayers, teams = balancedTeams)
        }
    }

    // Function to add a new player when a player joins the server
    private fun addNewPlayer(name: String) {
        val currentPlayers = _state.value.players
        val newPlayer = Player(
            id = currentPlayers.size,
            name = name,
            teamId = if (currentPlayers.size % 2 == 0) 1 else 2,
            hand = mutableListOf()
        )

        val updatedPlayers = (currentPlayers + newPlayer)
            .sortedBy { if (it.name == me) Int.MIN_VALUE else it.id }

        val updatedTeams = listOf(
            Team(id = 1, players = updatedPlayers.filter { it.teamId == 1 }),
            Team(id = 2, players = updatedPlayers.filter { it.teamId == 2 })
        )

        _state.update {
            it.copy(players = updatedPlayers, teams = updatedTeams)
        }
    }

    // Start the server to host the game
    fun startServer(port: Int = 8888) {
        if (isServerRunning) return

        isServerRunning = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(port)
                println("Hosting game on port $port")

                while (isServerRunning) {
                    val socket = serverSocket?.accept() ?: break
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    val playerName = reader.readLine()

                    println("Player joined: $playerName")

                    withContext(Dispatchers.Main) {
                        addNewPlayer(playerName)
                    }
                }
            } catch (e: Exception) {
                println("Server error: ${e.message}")
            }
        }
    }

    // Stop the server
    fun stopServer() {
        isServerRunning = false
        serverSocket?.close()
        serverSocket = null
    }

    // Function to generate the initial game state
    private fun generateDeck(): List<Card> {
        return Suit.entries.flatMap { suit ->
            Rank.entries.map { rank -> Card(suit, rank) }
        }
    }

    // Reset the game
    fun restartGame() {
        _state.value = generateInitialGameState()
    }

    // Start the game
    fun startGame() {
        _gameStarted.value = true
    }

    // Change the game mode
    fun changeGameMode(newMode: GameMode) {
        _state.update {
            it.copy(gameMode = newMode)
        }
    }

    // Set the player's name
    fun setPlayerName(name: String) {
        me = name
        _state.update { state ->
            state.copy(
                players = state.players.mapIndexed { index, player ->
                    if (index == 0) player.copy(name = name) else player
                }
            )
        }
    }

    // Initial game state
    fun generateInitialGameState(): GameState {
        return GameState(
            players = emptyList(),
            teams = listOf(
                Team(id = 1, players = emptyList()),
                Team(id = 2, players = emptyList())
            ),
            trumpSuit = null,
            trumpRevealed = false,
            gameMode = GameMode.CHOOSE_WHEN_EMPTY
        )
    }

    // Called when ViewModel is cleared (stop the server and clean up)
    override fun onCleared() {
        isServerRunning = false
        serverSocket?.close()
        super.onCleared()
    }

    fun selectTrump(suit: Suit) {
        TODO("Not yet implemented")
    }

    fun playTrick() {
        TODO("Not yet implemented")
    }
}
