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
import com.example.mindikot.ui.viewmodel.network.NetworkMessage // Correct import
import com.example.mindikot.ui.viewmodel.network.MessageType // Correct import
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

// Add required permissions to AndroidManifest.xml (Keep comments here for visibility)
// <uses-permission android:name="android.permission.INTERNET" />
// <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
// <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
// <uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />
// For Android 12+ (API 31+) NSD requires location permission:
// <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
// Or for just discovery:
// <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION"/>


// Main ViewModel class - Holds state and delegates network operations
class GameViewModel(
    // Make internal for access by extensions within the same module
    internal val applicationContext: Context
) : ViewModel() {

    // --- Game State ---
    // Use internal setter for modification by extensions if needed, but prefer methods
    val _state = MutableStateFlow(createInitialEmptyGameState())
    val state: StateFlow<GameState> = _state.asStateFlow()

    // --- Navigation/Events ---
    // Use internal SharedFlow for emitting events from extensions/methods
    internal val _navigateToResultScreen = MutableSharedFlow<RoundEvaluator.RoundResult>(replay = 0, extraBufferCapacity = 1)
    val navigateToResultScreen: SharedFlow<RoundEvaluator.RoundResult> = _navigateToResultScreen.asSharedFlow()

    internal val _showError = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 5) // Buffer for multiple errors
    val showError: SharedFlow<String> = _showError.asSharedFlow()

    // --- Game/Network Setup ---
    var isHost: Boolean = false
         // Only ViewModel internals can set this
    var requiredPlayerCount: Int = 4
        private set
    var localPlayerId: Int = -1 // -1 indicates not assigned yet
        private set // Make private, use internal setter method

    // Internal setter for localPlayerId
    internal fun setLocalPlayerIdInternal(id: Int) {
        localPlayerId = id
    }

    // Use internal StateFlow for modification by extensions/methods
    internal val _connectedPlayersCount = MutableStateFlow(1) // Includes host if hosting
    val connectedPlayersCount: StateFlow<Int> = _connectedPlayersCount.asStateFlow()

    internal val _gameStarted = MutableStateFlow(false)
    val gameStarted: StateFlow<Boolean> = _gameStarted.asStateFlow()

    // Internal setter for gameStarted
    internal fun setGameStartedInternal(started: Boolean) {
        _gameStarted.value = started
    }


    // --- Networking (Host - Properties managed here, logic in extensions) ---
    internal var serverSocket: ServerSocket? = null
    internal var servicePort: Int = 0
        private set
    // Internal setter for servicePort used by Host extension
    internal fun setServicePortInternal(port: Int) {
        servicePort = port
    }

    @Volatile // Ensure visibility across threads
    internal var isServerRunning: Boolean = false // Tracks if server *should* be running
    internal val clientSockets = ConcurrentHashMap<Int, Socket>() // PlayerId -> Socket
    internal val clientWriters = ConcurrentHashMap<Int, PrintWriter>() // PlayerId -> Writer
    internal val clientReaders = ConcurrentHashMap<Int, BufferedReader>() // PlayerId -> Reader
    internal val clientJobs = ConcurrentHashMap<Int, Job>() // PlayerId -> Listener Job

    // --- Networking (Client - Properties managed here, logic in extensions) ---
    internal var clientSocket: Socket? = null
    internal var clientWriter: PrintWriter? = null
    internal var clientReader: BufferedReader? = null
    internal var clientReaderJob: Job? = null
    @Volatile // Ensure visibility across threads
    internal var isConnectedToServer: Boolean = false
        // Allow extensions to set via internal setter
        internal set


    // --- NSD (Properties managed here, logic in extensions) ---
    internal var nsdManager: NsdManager? = null
    internal var registrationListener: NsdManager.RegistrationListener? = null // Host only
    internal var discoveryListener: NsdManager.DiscoveryListener? = null // Client only
    internal var nsdServiceNameRegistered: String? = null // Host only
        private set
    // Internal setter for NSD service name
    internal fun setNsdServiceNameRegisteredInternal(name: String?) {
        nsdServiceNameRegistered = name
    }


    // Use internal StateFlow for modification by extensions/methods
    internal val _discoveredHosts = MutableStateFlow<List<NsdServiceInfo>>(emptyList()) // Client only
    val discoveredHosts: StateFlow<List<NsdServiceInfo>> = _discoveredHosts.asStateFlow()
    internal val _hostIpAddress = MutableStateFlow<String?>(null) // Host display / Client connection info
    val hostIpAddress: StateFlow<String?> = _hostIpAddress.asStateFlow()
    internal val resolvingServices = ConcurrentHashMap<String, Boolean>() // Client: Track services being resolved

    // --- Serialization ---
    internal val gson = Gson() // Internal for use by extensions

    // ========================================================================    // INITIALIZATION & SETUP
    // ========================================================================
    /** Initializes game settings (player slots, mode) - Called by UI before starting host/client */
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

        // Create initial player list (placeholders for others)
        val players = (0 until playersNeeded).map { i ->
            Player(
                id = i,
                name = if (i == localPlayerId) playerName else "Waiting...", // Set local player name
                teamId = (i % 2) + 1, // Simple team assignment (1, 2, 1, 2...)
                hand = mutableListOf()
            )
        }
        val teams = listOf(
            Team(id = 1, players = players.filter { it.teamId == 1 }),
            Team(id = 2, players = players.filter { it.teamId == 2 })
        )

        // Reset relevant state fields
        _state.value = GameState(
            players = players,
            teams = teams,
            gameMode = mode,
            tricksWon = mutableMapOf(1 to 0, 2 to 0), // Initialize trick counts
            currentLeaderIndex = 0,
            trumpSuit = null,
            trumpRevealed = false,
            hiddenCard = null,
            currentTrickPlays = mutableListOf(),
            awaitingInputFromPlayerIndex = null,
            requiredInputType = null
        )
        _connectedPlayersCount.value = if (host) 1 else 0 // Host counts as 1 initially
        _gameStarted.value = false // Reset game started flag
        log("Initial GameState created for setup. isHost=$isHost, localPlayerId=$localPlayerId")

         // If hosting, broadcast the initial lobby state immediately
         // Although no clients are connected yet, this sets the host's view
         // Moved this logic inside startServerAndDiscovery to send state upon client connection
         // if (isHost) { broadcastGameState(_state.value) }

         if (!isHost) {
            // If joining, clear any previously discovered hosts
            _discoveredHosts.value = emptyList()
         }
    }

    /** HOST: Updates player name in the authoritative state and broadcasts the change (Runs on Main Thread) */
    internal fun updatePlayerName(playerId: Int, name: String) {
        if (!isHost) return // Only host updates names authoritatively
         if (name.isBlank()) {
             logError("Attempted to update Player $playerId name to blank. Ignoring.")
             return
         }

        log("Host: Updating Player $playerId name to '$name'")
        var nameChanged = false
        _state.update { currentState ->
            val playerIndex = currentState.players.indexOfFirst { it.id == playerId }
            if (playerIndex != -1) {
                 val currentName = currentState.players[playerIndex].name
                // Update only if different and not placeholder/disconnect messages
                if (currentName != name && currentName != "[Disconnected]" && !currentName.contains("[LEFT]")) {
                    nameChanged = true
                    val updatedPlayers = currentState.players.toMutableList()
                    updatedPlayers[playerIndex] = updatedPlayers[playerIndex].copy(name = name)
                    currentState.copy(players = updatedPlayers)
                } else {
                    currentState // No change needed
                }
            } else {
                 logError("Host: Cannot update name, Player $playerId not found in current state.")
                 currentState // Player not found
            }

        }
        if (nameChanged) {
            // Broadcast the updated state to all clients
            broadcastGameState(_state.value) // Use host extension method
        }
    }


    // ========================================================================    // GAME LOGIC PROCESSING (HOST ONLY)
    // ========================================================================
    /** HOST ONLY: Processes validated player input using the GameEngine and broadcasts the result */
    internal fun processGameInput(actingPlayerId: Int, playerInput: Any) {
        if (!isHost) {
            logError("processGameInput called on client device. Ignoring.")
            return
        }

        viewModelScope.launch(Dispatchers.Main.immediate) {
            // --- Get Current State ---
            // Make a temporary mutable copy to pass to the engine,
            // preserving the original _state.value until we are ready to update.
            // NOTE: This requires GameState properties that are mutable collections
            // to also be copied if the engine modifies them deeply.
            // For now, let's stick to copying *after* mutation as GameEngine expects mutable state.
            val currentState = _state.value

            // --- Pre-condition Checks ---
            // (Keep checks as they are)
            if (currentState.awaitingInputFromPlayerIndex != actingPlayerId) { /* ... */ return@launch }
            if (currentState.requiredInputType == null) { /* ... */ return@launch }

            log("Host: Processing input from Player $actingPlayerId ($playerInput), required: ${currentState.requiredInputType}")

            // --- Process with GameEngine ---
            var errorOccurred = false
            var stateAfterEngineProcessing: GameState = currentState // Initialize with current

            try {
                // GameEngine MUTATES currentState directly.
                // It might return the same mutated instance or potentially a new one
                // if it hits certain return paths (like re-requesting input after REVEAL).
                stateAfterEngineProcessing = GameEngine.processPlayerInput(currentState, playerInput)

            } catch (e: IllegalStateException) {
                logError("Host: Invalid move or state error processing input for Player $actingPlayerId: ${e.message}", e)
                sendMessageToClient(actingPlayerId, NetworkMessage(MessageType.ERROR, "Invalid Move: ${e.message}"))
                // Re-request input. Ensure requestInput returns a NEW state or copy after it.
                // Let's assume requestInput might mutate, so copy after.
                val requestedState = GameEngine.requestInput(currentState, actingPlayerId)
                _state.value = requestedState.copy() // Update state flow with a copy
                broadcastGameState(_state.value) // Broadcast the state requiring re-input
                return@launch // Stop further processing after error
            } catch (e: Exception) {
                logError("Host: Unexpected error processing game input for Player $actingPlayerId", e)
                sendMessageToClient(actingPlayerId, NetworkMessage(MessageType.ERROR, "Internal server error during your turn."))
                // Attempt to re-request input and copy the result
                val requestedState = GameEngine.requestInput(currentState, actingPlayerId)
                _state.value = requestedState.copy() // Update state flow with a copy
                broadcastGameState(_state.value) // Broadcast the state requiring re-input
                return@launch // Stop further processing after error
            }

            // --- Post-Processing State Update ---
            // Regardless of whether GameEngine returned the mutated original or a new state,
            // create a NEW copy here before updating the StateFlow.
//            val newState = stateAfterEngineProcessing.copy()
//            _state.value = newState // Assign the *new* copied instance to the StateFlow

            log("Host: State updated locally. Awaiting: ${_state.value.awaitingInputFromPlayerIndex}") // Log after local update

            // --- Check for Round End ---
            val roundEnded = _state.value.players.firstOrNull()?.hand?.isEmpty() == true && _state.value.currentTrickPlays.isEmpty()

            if (roundEnded) {
                log("Host: Round Ended. Evaluating...")
                val result = RoundEvaluator.evaluateRound(_state.value)
                log("Host: Round Result: Winner=${result.winningTeam?.id ?: "Draw"}, Kot=${result.isKot}")

                // Broadcast the final round state *after* local state update
                broadcastGameState(_state.value)

                delay(300)
                val emitted = _navigateToResultScreen.tryEmit(result)
                log("Host: Emitting navigation to result screen. Success: $emitted")
                // ... error handling for emit ...

            } else {
                // Round not ended, just broadcast the updated state *after* local state update
                broadcastGameState(_state.value)
            }
        }
    }

    // ========================================================================    // UI ACTION HANDLERS (Called by the UI on the specific device)
    // ========================================================================
    /** Called when the local player chooses a card to play */
    fun onCardPlayed(card: Card) {
         val localId = localPlayerId
         if (localId == -1) {
              viewModelScope.launch { _showError.emit("Cannot play: Player ID not assigned.") }
              return
         }
        log("UI Action: Card played: $card by Local Player $localId")
        val currentState = _state.value
        val myTurn = currentState.awaitingInputFromPlayerIndex == localId
        val expectedInput = currentState.requiredInputType

        // --- Basic Input Validation ---
        if (!myTurn) {
            logError("UI Action: Card played, but not player $localId's turn.")
            viewModelScope.launch { _showError.emit("Not your turn!") }
            return
        }
        if (expectedInput != InputType.PLAY_CARD && expectedInput != InputType.CHOOSE_TRUMP_SUIT) {
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

        // --- Client-side Valid Move Check (recommended for better UX) ---
        // This check is done *before* sending to the server (if client) or processing (if host).
        // The server (host) *must* re-validate regardless.
        val validMoves = GameEngine.determineValidMoves(
            playerHand = localPlayer.hand,
            currentTrickPlays = currentState.currentTrickPlays,
            trumpSuit = currentState.trumpSuit,
            trumpRevealed = currentState.trumpRevealed
        )
        if (!validMoves.contains(card)) {
            logError("UI Action: Invalid card $card played based on client-side check. Valid: $validMoves")
            viewModelScope.launch { _showError.emit("Invalid move (Rule violation).") }
            return
        }

        // --- Process or Send ---
        if (isHost) {
            // Host processes the input directly
            processGameInput(localId, card)
        } else {
            // Client sends the action to the host using client extension method
            sendMessageToServer(NetworkMessage(MessageType.PLAYER_ACTION, card))
            // Client UI might optimistically show the card moving, but waits for server state update
        }
    }

    /** Called when the local player chooses Reveal or Pass (Mode B) */
    fun onRevealOrPass(decision: GameEngine.Decision) {
         val localId = localPlayerId
         if (localId == -1) {
              viewModelScope.launch { _showError.emit("Cannot act: Player ID not assigned.") }
              return
         }
        log("UI Action: Reveal/Pass decision: $decision by Local Player $localId")
        val currentState = _state.value

        // --- Basic Input Validation ---
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


        // --- Process or Send ---
        if (isHost) {
            // Host processes the input directly
            processGameInput(localId, decision)
        } else {
            // Client sends the action to the host using client extension method
            sendMessageToServer(NetworkMessage(MessageType.PLAYER_ACTION, decision))
            // Client waits for server state update (which will then require PLAY_CARD)
        }
    }

    // ========================================================================    // UTILITY & LIFECYCLE
    // ========================================================================
    /** Creates an empty initial game state */
    internal fun createInitialEmptyGameState(): GameState {
        return GameState(
            players = emptyList(),
            teams = emptyList(),
            gameMode = GameMode.CHOOSE_WHEN_EMPTY, // Default mode
            currentLeaderIndex = 0,
            trumpSuit = null,
            trumpRevealed = false,
            hiddenCard = null,
            currentTrickPlays = mutableListOf(),
            awaitingInputFromPlayerIndex = null,
            requiredInputType = null,
            tricksWon = mutableMapOf()
        )
    }

    /** Called when ViewModel is cleared - ensures network cleanup */
    override fun onCleared() {
        log("GameViewModel Cleared. Cleaning up resources...")
        if (isHost) {
            // Stop hosting operations (server, NSD registration, client connections)
            stopServerAndDiscovery() // Use host extension method
        } else {
            // Stop client operations (discovery, connection to server)
            stopNsdDiscovery() // Use NSD extension method
            disconnectFromServer() // Use client extension method
        }
        // Cancel any other ongoing coroutines within the viewModelScope
        // viewModelScope.cancel() // This happens automatically when ViewModel is cleared
        super.onCleared()
        log("GameViewModel Cleanup Complete.")
    }
}