package com.example.mindikot.ui // Adjust package if needed

// Add Gson dependency to build.gradle (app level): implementation 'com.google.code.gson:gson:2.10.1' or later
// OR replace Gson with kotlinx.serialization (recommended)
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mindikot.core.engine.DeckGenerator
import com.example.mindikot.core.engine.GameEngine
import com.example.mindikot.core.engine.RoundEvaluator
import com.example.mindikot.core.model.*
import com.example.mindikot.core.state.GameState
import com.example.mindikot.core.state.InputType
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter // Needed for sending messages
import java.net.ServerSocket
import java.net.Socket // Needed for client connections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class GameViewModel : ViewModel() {

    // --- Game State ---
    // Start with an empty state, will be initialized by host/client setup
    private val _state = MutableStateFlow(createInitialEmptyGameState())
    val state: StateFlow<GameState> = _state.asStateFlow()

    // --- Navigation/Events ---
    // Emits the result of the round for navigation/display
    private val _navigateToResultScreen = MutableSharedFlow<RoundEvaluator.RoundResult>()
    val navigateToResultScreen: SharedFlow<RoundEvaluator.RoundResult> =
        _navigateToResultScreen.asSharedFlow()

    // Emits error messages for the UI
    private val _showError = MutableSharedFlow<String>()
    val showError: SharedFlow<String> = _showError.asSharedFlow()

    // --- Game/Network Setup ---
    var isHost: Boolean = false
        private set
    var requiredPlayerCount: Int = 4 // Default
        private set
    var localPlayerId: Int = -1 // ID of the player on this device (-1 means not assigned)
        private set

    // Tracks connected players for lobby UI
    private val _connectedPlayersCount = MutableStateFlow(0)
    val connectedPlayersCount: StateFlow<Int> = _connectedPlayersCount

    // Tracks if the actual gameplay has started (cards dealt)
    private val _gameStarted = MutableStateFlow(false)
    val gameStarted: StateFlow<Boolean> = _gameStarted

    // --- Networking (Host) ---
    private var serverSocket: ServerSocket? = null
    private var isServerRunning = false
    private val clientSockets = ConcurrentHashMap<Int, Socket>() // Map Player ID -> Socket
    private val clientWriters = ConcurrentHashMap<Int, PrintWriter>() // Map Player ID -> Writer
    private val clientReaders = ConcurrentHashMap<Int, BufferedReader>() // Map Player ID -> Reader
    private val clientJobs = ConcurrentHashMap<Int, Job>() // Map Player ID -> Listener Job

    // --- Networking (Client) ---
    private var clientSocket: Socket? = null
    private var clientWriter: PrintWriter? = null
    private var clientReader: BufferedReader? = null
    private var clientReaderJob: Job? = null
    private var isConnectedToServer = false

    // --- Serialization ---
    // Using Gson for simplicity, consider kotlinx.serialization for production
    private val gson = Gson()

    // --- Logging ---
    private fun log(message: String, tag: String = "GameViewModel") {
        println("[$tag] $message") // Simple console logging
    }
    private fun logError(message: String, error: Throwable? = null) {
        val errorMsg = error?.message?.let { ": $it" } ?: ""
        println("[GameViewModel ERROR] $message$errorMsg")
    }

    // ========================================================================
    // HOST FUNCTIONS
    // ========================================================================

    /** HOST: Initializes the game settings and player slots before starting the server. */
    fun initializeGameSettings(
        playerName: String,
        mode: GameMode,
        host: Boolean = true,
        playersNeeded: Int = 4
    ) {
        log("Initializing game settings as Host.")
        isHost = host
        requiredPlayerCount = playersNeeded
        localPlayerId = 0 // Host is always Player 0

        // Create player placeholders
        val players =
            (0 until playersNeeded).map { i ->
                Player(
                    id = i,
                    name =
                        if (i == 0) playerName
                        else "Waiting...", // Host name, others waiting
                    teamId = (i % 2) + 1, // Alternate teams 1 and 2
                    hand = mutableListOf() // Hands dealt only when game starts
                )
            }

        val teams =
            listOf(
                Team(id = 1, players = players.filter { it.teamId == 1 }),
                Team(id = 2, players = players.filter { it.teamId == 2 })
            )

        // Make sure GameState includes all fields, including tricksWon map
        _state.value =
            GameState(
                players = players,
                teams = teams,
                gameMode = mode,
                currentLeaderIndex = 0, // Start with player 0
                trumpSuit = null,
                trumpRevealed = false,
                hiddenCard = null,
                currentTrickPlays = mutableListOf(),
                awaitingInputFromPlayerIndex = null,
                requiredInputType = null,
                tricksWon = mutableMapOf(1 to 0, 2 to 0) // Initialize trick counts
            )
        _connectedPlayersCount.value = 1 // Host counts as connected
        log("Initial GameState created for host setup.")
    }

    /** HOST: Starts the server socket and listens for client connections. */
    fun startServer(port: Int = 8888) {
        if (isServerRunning || !isHost) return
        log("Starting server on port $port...")
        isServerRunning = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(port)
                log("Server started. Waiting for ${requiredPlayerCount - 1} players...")

                while (clientSockets.size < requiredPlayerCount - 1 && isServerRunning) {
                    val socket = serverSocket?.accept() ?: break // Wait for a client
                    val currentClientCount = clientSockets.size
                    val assignedPlayerId = currentClientCount + 1 // Player IDs 1, 2, 3...

                    log("Client connected, assigning Player ID $assignedPlayerId")

                    try {
                        val writer = PrintWriter(socket.getOutputStream(), true)
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                        // Store connection details BEFORE starting listener
                        clientSockets[assignedPlayerId] = socket
                        clientWriters[assignedPlayerId] = writer
                        clientReaders[assignedPlayerId] = reader

                        // 1. Send the assigned player ID FIRST
                        sendMessageToClient(
                            assignedPlayerId,
                            NetworkMessage(MessageType.ASSIGN_ID, assignedPlayerId)
                        )

                        // 2. Start listening for messages from this specific client
                        listenToClient(assignedPlayerId, reader)

                        // 3. Send the current GameState (lobby state) - Important for client to see lobby
                        sendMessageToClient(
                            assignedPlayerId,
                            NetworkMessage(MessageType.GAME_STATE_UPDATE, _state.value)
                        )

                        // Update connected player count (on Main thread)
                        withContext(Dispatchers.Main) {
                            _connectedPlayersCount.value = clientSockets.size + 1 // +1 for host
                        }
                        log(
                            "Player ID $assignedPlayerId assigned and listener started. Connected: ${_connectedPlayersCount.value}/${requiredPlayerCount}"
                        )

                        // Check if lobby is full after this connection
                        if (_connectedPlayersCount.value == requiredPlayerCount) {
                            log("All players connected!")
                            // Automatically start game preparation
                            prepareAndBroadcastInitialState()
                            break // Stop accepting connections once full
                        }

                    } catch (e: Exception) {
                        logError("Error setting up connection for Player $assignedPlayerId", e)
                        // Clean up potentially partial connection
                        runCatching { socket.close() }
                        // No need to removeClient here, as they weren't fully added/tracked yet
                    }
                }

                log("Stopped accepting connections (lobby full or server stopped).")

            } catch (e: Exception) {
                if (isServerRunning) {
                    logError("Server error", e)
                    withContext(Dispatchers.Main) { _showError.emit("Server Error: ${e.message}") }
                    stopServer() // Clean up on error
                }
            } finally {
                log("Server listener loop finished.")
                isServerRunning = false // Ensure flag is reset if loop exits
            }
        }
    }

    /** HOST: Starts a listener coroutine for a specific client. */
    private fun listenToClient(playerId: Int, reader: BufferedReader) {
        clientJobs[playerId] =
            viewModelScope.launch(Dispatchers.IO) {
                log("Listener started for Player $playerId.")
                try {
                    while (isActive) { // Loop while coroutine is active
                        val messageJson =
                            reader.readLine() ?: break // null means connection closed
                        log("Received from Player $playerId: $messageJson")
                        try {
                            val message = gson.fromJson(messageJson, NetworkMessage::class.java)
                            // Process message on Main thread for state safety
                            withContext(
                                Dispatchers.Main.immediate
                            ) { // Use immediate for faster processing if safe
                                handleClientMessage(playerId, message)
                            }
                        } catch (e: JsonSyntaxException) {
                            logError("JSON Parse Error from Player $playerId: ${e.message}")
                        } catch (e: Exception) {
                            logError("Error handling message from Player $playerId: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    if (isActive) { // Avoid logging errors during cancellation
                        logError("Error reading from Player $playerId socket", e)
                    }
                } finally {
                    log("Listener stopped for Player $playerId.")
                    // Ensure cleanup happens on the Main thread safely
                    withContext(Dispatchers.Main) {
                        removeClient(playerId)
                    }
                }
            }
        // Optional: Add completion handler for debugging
        clientJobs[playerId]?.invokeOnCompletion { throwable ->
            if (throwable != null && throwable !is CancellationException) {
                logError("Listener job for Player $playerId completed with error", throwable)
            } else {
                log("Listener job for Player $playerId completed normally or cancelled.")
            }
            // Redundant cleanup call here if finally block is guaranteed, but can be defensive
            viewModelScope.launch(Dispatchers.Main) { removeClient(playerId) }
        }
    }

    /** HOST: Handles messages received from a specific client. */
    private fun handleClientMessage(playerId: Int, message: NetworkMessage) {
        log("Handling message: ${message.type} from Player $playerId")
        when (message.type) {
            MessageType.PLAYER_ACTION -> {
                if (_state.value.awaitingInputFromPlayerIndex == playerId) {
                    val expectedType = _state.value.requiredInputType
                    try {
                        val actionData: Any? = // Deserialize based on expected type
                            when (expectedType) {
                                InputType.PLAY_CARD -> gson.fromJson(gson.toJson(message.data), Card::class.java)
                                InputType.REVEAL_OR_PASS -> gson.fromJson(gson.toJson(message.data), GameEngine.Decision::class.java)
                                InputType.CHOOSE_TRUMP_SUIT -> gson.fromJson(gson.toJson(message.data), Card::class.java)
                                null -> null // Error case handled below
                            }

                        if (actionData != null) {
                            processGameInput(playerId, actionData) // Pass Player ID for context
                        } else {
                            logError("Failed to parse PLAYER_ACTION data for expected type $expectedType from Player $playerId")
                            sendMessageToClient(playerId, NetworkMessage(MessageType.ERROR, "Invalid action data format."))
                        }
                    } catch (e: Exception) {
                        logError("Error deserializing PLAYER_ACTION data from Player $playerId", e)
                        sendMessageToClient(playerId, NetworkMessage(MessageType.ERROR, "Error processing your action."))
                    }
                } else {
                    log("Received action from Player $playerId but it's not their turn (expected ${_state.value.awaitingInputFromPlayerIndex}).")
                    sendMessageToClient(playerId, NetworkMessage(MessageType.ERROR, "Not your turn"))
                }
            }
            MessageType.PLAYER_NAME -> {
                val name = message.data as? String ?: "Player $playerId"
                updatePlayerName(playerId, name) // Update name and broadcast state
            }
            else -> log("Received unhandled message type: ${message.type} from Player $playerId")
        }
    }

    /** HOST: Deals cards, sets hidden card (Mode B), updates state, and broadcasts. */
    private fun prepareAndBroadcastInitialState() {
        // Ensure this runs on Main thread for state safety
        viewModelScope.launch(Dispatchers.Main.immediate) {
            log("Preparing initial game state for ${requiredPlayerCount} players...")
            val currentPlayers = _state.value.players
            if (currentPlayers.size != requiredPlayerCount) {
                logError("Cannot prepare initial state: Incorrect number of players (${currentPlayers.size}/${requiredPlayerCount})")
                _showError.emit("Cannot start game - waiting for players.")
                return@launch
            }

            val deck = DeckGenerator.generateDeck(requiredPlayerCount)
            var hiddenCard: Card? = null

            // Handle hidden card for Mode B *before* dealing
            if (_state.value.gameMode == GameMode.FIRST_CARD_HIDDEN) {
                if (deck.isNotEmpty()) {
                    hiddenCard = deck.removeAt(0) // Take from top
                    log("Hidden card set (Mode B): ${hiddenCard.suit}")
                } else {
                    logError("Deck empty before hidden card selection!")
                    _showError.emit("Error starting game: Deck empty.")
                    // Maybe stop server or reset lobby state
                    return@launch
                }
            }

            // Deal cards
            val updatedPlayers = currentPlayers.toMutableList()
            val cardsPerPlayer = deck.size / requiredPlayerCount
            if (deck.size % requiredPlayerCount != 0) {
                logError("Deck size ${deck.size} not evenly divisible by $requiredPlayerCount players after potential hidden card!")
                // Proceeding, but some players might get fewer cards if deck is wrong size
            }

            for (i in 0 until requiredPlayerCount) {
                val handCards = deck.take(cardsPerPlayer).toMutableList()
                if (i < updatedPlayers.size) {
                    updatedPlayers[i] = updatedPlayers[i].copy(hand = handCards)
                    // More efficient removal needed if deck is large, but for ~52 cards this is ok
                    deck.removeAll(handCards.toSet())
                } else {
                    logError("Player index $i out of bounds during dealing (size ${updatedPlayers.size})")
                }
            }
            log("Cards dealt. Remaining deck size: ${deck.size}") // Should be 0

            // Update GameState - Ensure ALL fields are correctly set/reset for a new round
            var initialState = _state.value.copy(
                players = updatedPlayers,
                hiddenCard = hiddenCard,
                tricksWon = mutableMapOf(1 to 0, 2 to 0), // Reset trick counts
                currentTrickPlays = mutableListOf(),      // Ensure trick plays are clear
                trumpSuit = null,                         // Ensure trump is reset
                trumpRevealed = false,                    // Ensure trump revealed is reset
                currentLeaderIndex = 0                    // Reset leader to player 0
            )

            // Set initial input request for the first leader
            initialState = GameEngine.requestInput(initialState, initialState.currentLeaderIndex)

            _state.value = initialState // Update the StateFlow

            // Broadcast the initial state to all clients
            broadcastGameState(_state.value)
            log("Initial GameState broadcast.")

            // Mark game as started AFTER broadcasting state
            _gameStarted.value = true
        }
    }

    /** HOST: Broadcasts the GameState to all connected clients. */
    private fun broadcastGameState(gameState: GameState) {
        if (!isHost) return
        val message = NetworkMessage(MessageType.GAME_STATE_UPDATE, gameState)
        log("Broadcasting GameState to ${clientWriters.size} clients...")
        clientWriters.forEach { (id, _) -> // Iterate keys safely
            sendMessageToClient(id, message)
        }
        log("Broadcast attempt complete.")
    }

    /** HOST: Sends a specific message to a single client. Handles potential errors. */
    private fun sendMessageToClient(playerId: Int, message: NetworkMessage) {
        if (!isHost) return
        val writer = clientWriters[playerId]
        if (writer == null) {
            log("Cannot send message, writer not found for Player $playerId (already removed?).")
            return
        }
        viewModelScope.launch(Dispatchers.IO) { // Send on IO thread
            try {
                val messageJson = gson.toJson(message) // Ensure thread safety if gson instance is shared without care
                synchronized(writer) { // Synchronize access to the writer
                    writer.println(messageJson)
                }
                if (writer.checkError()) { // Check for errors after writing
                    throw Exception("PrintWriter error occurred.")
                }
                log("Sent to Player $playerId: ${message.type}")
            } catch (e: Exception) {
                logError("Error sending message to Player $playerId", e)
                // Error likely means client disconnected, trigger removal
                withContext(Dispatchers.Main) { removeClient(playerId) }
            }
        }
    }

    /** HOST: Cleans up resources associated with a disconnected or removed client. */
    private fun removeClient(playerId: Int) {
        // Ensure running on Main thread for state safety
        viewModelScope.launch(Dispatchers.Main.immediate) { // Use immediate if called from IO completion handler
            if (!clientSockets.containsKey(playerId)) {
                log("Attempted to remove Player $playerId, but already removed.")
                return@launch // Already removed
            }

            log("Removing client Player $playerId...")
            clientJobs[playerId]?.cancel() // Cancel listener job FIRST
            clientJobs.remove(playerId)

            // Close streams and socket safely
            runCatching { clientWriters[playerId]?.close() }.onFailure { logError("Error closing writer for $playerId", it) }
            runCatching { clientReaders[playerId]?.close() }.onFailure { logError("Error closing reader for $playerId", it) }
            runCatching { clientSockets[playerId]?.close() }.onFailure { logError("Error closing socket for $playerId", it) }

            // Remove from maps
            clientWriters.remove(playerId)
            clientReaders.remove(playerId)
            clientSockets.remove(playerId)

            // Update connected count
            _connectedPlayersCount.value = clientSockets.size + 1

            log("Player $playerId removed. Connected: ${_connectedPlayersCount.value}/${requiredPlayerCount}")

            // Handle game interruption
            if (_gameStarted.value) {
                _showError.emit("Player ${state.value.players.find { it.id == playerId }?.name ?: playerId} disconnected. Game interrupted.")
                // Option: Stop the game immediately
                stopServer() // Or implement more graceful handling later
            } else {
                // If still in lobby, update player list to show disconnected state
                _state.update { state ->
                    val updatedPlayers = state.players.map {
                        if (it.id == playerId) it.copy(name = "[Disconnected]", hand = mutableListOf()) // Mark as disconnected, clear hand
                        else it
                    }
                    state.copy(players = updatedPlayers)
                }
                broadcastGameState(_state.value) // Inform others player left lobby
            }
        }
    }

    /** HOST: Stops the server and cleans up all connections. */
    fun stopServer() {
        if (!isHost || !isServerRunning) return // Prevent multiple calls or client calls
        log("Stopping server...")
        isServerRunning = false // Signal loops to stop

        // Close server socket first to prevent new connections
        runCatching { serverSocket?.close() }.onFailure { logError("Error closing server socket", it)}
        serverSocket = null

        // Cancel all client listening jobs
        clientJobs.values.forEach { it.cancel() }
        clientJobs.clear()

        // Close all client connections
        // Create a temporary list of keys to avoid ConcurrentModificationException
        val clientIds = clientSockets.keys.toList()
        clientIds.forEach { id ->
            // Use the removeClient function for proper cleanup of each client
            removeClient(id) // This will handle closing sockets/streams and removing from maps
        }
        // Double check maps are clear (should be by removeClient)
        clientSockets.clear()
        clientWriters.clear()
        clientReaders.clear()

        // Reset state after cleanup
        _connectedPlayersCount.value = 0
        _gameStarted.value = false
        _state.value = createInitialEmptyGameState()
        log("Server stopped and connections closed.")
    }


    // ========================================================================
    // CLIENT FUNCTIONS
    // ========================================================================

    /** CLIENT: Connects to the host server. */
    fun connectToServer(hostAddress: String, port: Int = 8888, playerName: String) {
        if (isConnectedToServer || isHost) return
        log("Client: Attempting to connect to $hostAddress:$port...")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Explicitly close any existing connection attempt first
                disconnectFromServer()
                delay(100) // Short delay before reconnecting

                clientSocket = Socket(hostAddress, port)
                clientWriter = PrintWriter(clientSocket!!.getOutputStream(), true)
                clientReader = BufferedReader(InputStreamReader(clientSocket!!.getInputStream()))
                isConnectedToServer = true
                log("Client: Connected successfully.")

                // Start listening for messages from the server
                listenToServer()

                // Send player name to host (important for host to identify player)
                sendMessageToServer(NetworkMessage(MessageType.PLAYER_NAME, playerName))

                // UI should navigate to a "Waiting in Lobby" screen here

            } catch (e: Exception) {
                logError("Client: Connection failed", e)
                isConnectedToServer = false
                withContext(Dispatchers.Main) {
                    _showError.emit("Connection failed: ${e.message}")
                }
                disconnectFromServer() // Ensure cleanup after failure
            }
        }
    }

    /** CLIENT: Listens for messages from the server. */
    private fun listenToServer() {
        clientReaderJob?.cancel() // Ensure only one listener runs
        clientReaderJob = viewModelScope.launch(Dispatchers.IO) {
            log("Client: Listener started.")
            try {
                while (isActive) {
                    val messageJson = clientReader?.readLine() ?: break // null means connection closed gracefully
                    log("Client: Received from server: $messageJson")
                    try {
                        val message = gson.fromJson(messageJson, NetworkMessage::class.java)
                        // Process message on Main thread for UI/State updates
                        withContext(Dispatchers.Main.immediate) {
                            handleServerMessage(message)
                        }
                    } catch (e: JsonSyntaxException) {
                        logError("Client: JSON Parse Error from server", e)
                    } catch (e: Exception) {
                        logError("Client: Error handling message from server", e)
                    }
                }
            } catch (e: Exception) {
                // Log error only if the job is still active (not cancelled intentionally)
                if (isActive) {
                    logError("Client: Error reading from server socket", e)
                    withContext(Dispatchers.Main) {
                        _showError.emit("Lost connection to server.")
                    }
                }
            } finally {
                log("Client: Listener stopped.")
                // Trigger disconnection logic on the main thread
                withContext(Dispatchers.Main) {
                    disconnectFromServer() // Ensure full cleanup
                }
            }
        }
        clientReaderJob?.invokeOnCompletion { throwable ->
            log("Client listener job completed.")
            if (throwable != null && throwable !is CancellationException) {
                logError("Client listener job completed with error", throwable)
            }
            // Disconnect when the listener job ends for any reason
            viewModelScope.launch(Dispatchers.Main) { disconnectFromServer() }
        }
    }

    /** CLIENT: Handles messages received from the server. */
    private fun handleServerMessage(message: NetworkMessage) {
        log("Client: Handling message: ${message.type}")
        when (message.type) {
            MessageType.ASSIGN_ID -> {
                // Server assigned our player ID
                val id = (message.data as? Double)?.toInt() ?: -1 // Gson quirk with numbers
                if (id != -1 && localPlayerId == -1) { // Assign only if not already assigned
                    localPlayerId = id
                    log("Client: Assigned Player ID: $localPlayerId")
                } else if (localPlayerId != -1 && localPlayerId != id) {
                    logError("Client: Received conflicting Player ID from server (Current: $localPlayerId, Received: $id).")
                    // Might indicate a server issue or reconnect problem. Consider disconnecting.
                } else if (id == -1) {
                    logError("Client: Received invalid Player ID (-1) from server.")
                }
            }
            MessageType.GAME_STATE_UPDATE -> {
                // Server sent updated GameState
                try {
                    // Use TypeToken for complex generic types if needed, but re-serializing often works
                    val gameStateJson = gson.toJson(message.data) // Convert Any back to JSON
                    val updatedState = gson.fromJson(gameStateJson, GameState::class.java) // Deserialize specific type

                    // Basic validation of received state
                    if (updatedState.players.isEmpty()) {
                        logError("Client: Received empty player list in GAME_STATE_UPDATE.")
                        return // Ignore potentially invalid state
                    }

                    _state.value = updatedState // Update local state authoritative from server

                    // Update connected players count based on received state (count non-waiting/disconnected)
                    _connectedPlayersCount.value = updatedState.players.count {
                        it.name != "Waiting..." && it.name != "[Disconnected]"
                    }

                    log("Client: GameState updated. Awaiting input from: ${updatedState.awaitingInputFromPlayerIndex}")

                    // Determine if game started based on hands being dealt
                    val myHand = updatedState.players.find { it.id == localPlayerId }?.hand
                    if (!_gameStarted.value && myHand?.isNotEmpty() == true) {
                        _gameStarted.value = true
                        log("Client: Game Started (received non-empty hand).")
                    } else if (_gameStarted.value && myHand?.isEmpty() == true && updatedState.currentTrickPlays.isEmpty()) {
                        // If game was started but now hand is empty and no trick ongoing, likely round end
                        log("Client: Received state potentially indicating round end.")
                        // The host should ideally send a specific ROUND_END message with results
                        // Or client UI navigates based on _navigateToResultScreen which host triggers somehow
                    }

                } catch (e: Exception) {
                    logError("Client: Error deserializing GAME_STATE_UPDATE", e)
                }
            }
            MessageType.REQUEST_INPUT -> {
                // Optional: Server explicitly asks for input
                val requestedPlayerId = (message.data as? Double)?.toInt() ?: -1
                if (requestedPlayerId == localPlayerId) {
                    log("Client: Server explicitly requested input. UI should check GameState.")
                    // UI should already be reactive to GameState.awaitingInputFromPlayerIndex
                }
            }
            MessageType.ERROR -> {
                val errorMsg = message.data as? String ?: "Unknown error from server"
                logError("Client: Received error message from server: $errorMsg")
                viewModelScope.launch { _showError.emit(errorMsg) } // Show error to user
            }
            // Other message types might be needed (e.g., ROUND_RESULT, GAME_OVER)
            else -> log("Client: Received unhandled message type: ${message.type}")
        }
    }

    /** CLIENT: Sends a message to the host server. */
    private fun sendMessageToServer(message: NetworkMessage) {
        if (!isConnectedToServer || isHost || clientWriter == null) {
            log("Client: Cannot send message. Not connected or is host.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val messageJson = gson.toJson(message)
                synchronized(clientWriter!!) { // Synchronize access
                    clientWriter?.println(messageJson)
                }
                if (clientWriter?.checkError() == true) {
                    throw Exception("PrintWriter error occurred.")
                }
                log("Client: Sent message: ${message.type}")
            } catch (e: Exception) {
                logError("Client: Error sending message", e)
                // Attempt to handle gracefully, maybe disconnect
                withContext(Dispatchers.Main) {
                    _showError.emit("Failed to send action. Check connection.")
                    // Consider triggering disconnect if send fails
                    // disconnectFromServer()
                }
            }
        }
    }

    /** CLIENT: Disconnects from the server and cleans up resources. */
    fun disconnectFromServer() {
        if (isHost) return // Host shouldn't call this
        if (!isConnectedToServer && clientSocket == null) return // Already disconnected

        log("Client: Disconnecting...")
        isConnectedToServer = false // Set flag immediately
        clientReaderJob?.cancel() // Cancel listener job
        clientReaderJob = null

        // Close streams and socket safely
        runCatching { clientWriter?.close() }.onFailure{ logError("Error closing client writer", it)}
        runCatching { clientReader?.close() }.onFailure{ logError("Error closing client reader", it)}
        runCatching { clientSocket?.close() }.onFailure{ logError("Error closing client socket", it)}

        clientSocket = null
        clientWriter = null
        clientReader = null

        // Reset client-specific state
        _gameStarted.value = false
        _state.value = createInitialEmptyGameState() // Reset state
        localPlayerId = -1
        _connectedPlayersCount.value = 0
        log("Client: Disconnected and cleaned up.")
    }


    // ========================================================================
    // GAME LOGIC PROCESSING (HOST ONLY)
    // ========================================================================

    /**
     * HOST ONLY: Processes player input using the GameEngine and broadcasts the result.
     * @param actingPlayerId The ID of the player whose action is being processed.
     * @param playerInput The action data (Card, Decision, etc.).
     */
    private fun processGameInput(actingPlayerId: Int, playerInput: Any) {
        if (!isHost) {
            logError("Client attempted local game processing!")
            return
        }

        viewModelScope.launch(Dispatchers.Main.immediate) { // Process immediately on Main thread
            val currentState = _state.value // Get current state

            // --- Basic Validation ---
            if (currentState.awaitingInputFromPlayerIndex != actingPlayerId) {
                logError("Input processed for wrong player. Expected: ${currentState.awaitingInputFromPlayerIndex}, Got: $actingPlayerId")
                sendMessageToClient(actingPlayerId, NetworkMessage(MessageType.ERROR, "Not your turn"))
                return@launch // Ignore input
            }
            // Could add validation: is playerInput type compatible with currentState.requiredInputType?

            // --- Process with GameEngine ---
            log("Host: Processing input from Player $actingPlayerId: $playerInput")
            try {
                // GameEngine modifies the state object directly in this implementation
                GameEngine.processPlayerInput(currentState, playerInput)

                // Update the StateFlow *after* engine modification
                _state.value = currentState // This triggers UI updates via collectAsState

                // Check for round end condition *after* state update
                val roundEnded = currentState.players.firstOrNull()?.hand?.isEmpty() == true && currentState.currentTrickPlays.isEmpty()

                if (roundEnded) {
                    log("Host: Round Ended. Evaluating...")
                    val result = RoundEvaluator.evaluateRound(currentState) // Evaluate the final state
                    log("Host: Round Result: Winner=${result.winningTeam?.id ?: "Draw"}, Kot=${result.isKot}")
                    // TODO: Update actual game scores (if tracking across rounds)

                    // Broadcast the FINAL state of the round
                    broadcastGameState(currentState)
                    // Delay slightly for clients to process final state, then emit result event
                    delay(200) // Adjust as needed
                    _navigateToResultScreen.emit(result) // Signal host UI / potentially broadcast result separately

                    // TODO: Implement logic for next round (reset state, deal again) or end game
                    // resetForNextRound() ?
                } else {
                    // Round continues, broadcast the updated state (showing next player's turn etc.)
                    broadcastGameState(currentState)
                }

            } catch (e: IllegalStateException) {
                logError("Host: Invalid move detected by GameEngine for Player $actingPlayerId: ${e.message}")
                sendMessageToClient(actingPlayerId, NetworkMessage(MessageType.ERROR, "Invalid Move: ${e.message}"))
                // State was potentially mutated by engine before error, need to revert or re-request carefully
                // For simplicity, just re-request from the same player based on the *original* state before the failed attempt.
                // This requires passing a copy to GameEngine or having a revert mechanism.
                // Safer option: Re-request input based on the *current* (potentially partially modified) state.
                _state.value = GameEngine.requestInput(_state.value, actingPlayerId) // Re-request input
                broadcastGameState(_state.value) // Broadcast state showing it's still their turn

            } catch (e: Exception) {
                logError("Host: Unexpected error processing game input for Player $actingPlayerId", e)
                _showError.emit("Internal Server Error.") // Generic error for host UI
                // Consider stopping the game or notifying clients of a server error
            }
        }
    }

    // ========================================================================
    // UI ACTION HANDLERS (Called by the UI on the specific device)
    // ========================================================================

    /** Called when the local player chooses a card to play */
    fun onCardPlayed(card: Card) {
        log("UI Action: Card played: $card by Local Player $localPlayerId")
        val currentState = _state.value
        val myTurn = currentState.awaitingInputFromPlayerIndex == localPlayerId
        val expectedInput = currentState.requiredInputType

        if (!myTurn) {
            logError("Attempted to play card when not local player's turn.")
            viewModelScope.launch { _showError.emit("Not your turn!") }
            return
        }
        // Check if PLAY_CARD or CHOOSE_TRUMP_SUIT (which involves playing a card) is expected
        if (expectedInput != InputType.PLAY_CARD && expectedInput != InputType.CHOOSE_TRUMP_SUIT) {
            logError("Attempted to play card when expected input was $expectedInput")
            viewModelScope.launch { _showError.emit("Cannot play a card right now.") }
            return
        }
        // Basic check: Does the player have the card?
        if (currentState.players.find { it.id == localPlayerId }?.hand?.contains(card) != true) {
            logError("Attempted to play card not in hand: $card")
            viewModelScope.launch { _showError.emit("Card not in hand!") }
            return
        }
        // More complex validation (following suit, post-reveal trump) should ideally happen
        // *before* sending/processing, possibly using GameEngine.determineValidMoves.
        // For now, we rely on GameEngine in processGameInput for final validation.

        if (isHost) {
            processGameInput(localPlayerId, card) // Host processes directly
        } else {
            sendMessageToServer(NetworkMessage(MessageType.PLAYER_ACTION, card)) // Client sends to host
        }
    }

    /** Called when the local player chooses Reveal or Pass (Mode B) */
    fun onRevealOrPass(decision: GameEngine.Decision) {
        log("UI Action: Reveal/Pass decision: $decision by Local Player $localPlayerId")
        val currentState = _state.value
        if (currentState.awaitingInputFromPlayerIndex != localPlayerId || currentState.requiredInputType != InputType.REVEAL_OR_PASS) {
            logError("Attempted Reveal/Pass at wrong time/turn. Expected: ${currentState.requiredInputType}")
            viewModelScope.launch { _showError.emit("Cannot Reveal or Pass now.") }
            return
        }

        if (isHost) {
            processGameInput(localPlayerId, decision)
        } else {
            sendMessageToServer(NetworkMessage(MessageType.PLAYER_ACTION, decision))
        }
    }

    // ========================================================================
    // UTILITY & LIFECYCLE
    // ========================================================================

    /** Creates an empty initial game state */
    private fun createInitialEmptyGameState(): GameState {
        return GameState(
            players = emptyList(),
            teams = emptyList(),
            gameMode = GameMode.CHOOSE_WHEN_EMPTY, // Default mode
            // Initialize all fields, including the map
            tricksWon = mutableMapOf()
        )
    }

    /** HOST: Updates player name in the authoritative state and broadcasts the change */
    private fun updatePlayerName(playerId: Int, name: String) {
        if (!isHost) return // Only host modifies the authoritative state
        log("Host: Updating Player $playerId name to '$name'")
        var nameUpdated = false
        _state.update { currentState ->
            val updatedPlayers = currentState.players.map {
                if (it.id == playerId && it.name != name) {
                    nameUpdated = true
                    it.copy(name = name)
                } else it
            }
            // Only update state if name actually changed
            if (nameUpdated) currentState.copy(players = updatedPlayers) else currentState
        }
        // Broadcast the updated state only if the name actually changed
        if (nameUpdated) {
            broadcastGameState(_state.value)
        }
    }

    /** Called when ViewModel is cleared - ensures network cleanup */
    override fun onCleared() {
        log("GameViewModel Cleared.")
        if (isHost) {
            stopServer() // Host stops server and cleans up clients
        } else {
            disconnectFromServer() // Client disconnects
        }
        super.onCleared()
    }
}

// ========================================================================
// DATA CLASSES FOR NETWORKING
// ========================================================================

/** Defines the type of message being sent over the network. */
enum class MessageType {
    ASSIGN_ID,          // Server -> Client: Your assigned player ID {data: Int}
    GAME_STATE_UPDATE,  // Server -> Client: The current full GameState {data: GameState}
    PLAYER_ACTION,      // Client -> Server: Player performed an action {data: Card or Decision}
    // REQUEST_INPUT is optional if clients purely rely on GameState.awaitingInputFromPlayerIndex
    REQUEST_INPUT,      // Server -> Client: It's your turn, input needed {data: PlayerID}
    PLAYER_NAME,        // Client -> Server: Sending player's chosen name {data: String}
    ERROR               // Server -> Client: An error occurred {data: String}
    // Consider adding: ROUND_RESULT, GAME_OVER messages
}

/** Represents a message sent between the host and clients. */
data class NetworkMessage(
    val type: MessageType,
    /**
     * Data payload. Needs careful serialization/deserialization based on 'type'.
     * Examples: Int, GameState, Card, GameEngine.Decision, String.
     */
    val data: Any? = null
)