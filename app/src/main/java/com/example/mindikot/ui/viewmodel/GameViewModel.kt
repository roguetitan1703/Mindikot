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