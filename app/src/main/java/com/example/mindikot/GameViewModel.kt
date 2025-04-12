package com.example.mindikot.ui // Adjust package if needed

import android.Manifest // Required for NSD permissions
import android.content.Context // Needed for NsdManager
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
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
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket // Needed for client connections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

// Add required permissions to AndroidManifest.xml:
// <uses-permission android:name="android.permission.INTERNET" />
// <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
// <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" /> // Good practice to
// check network state
// For NSD discovery/advertising:
// <uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />
// For Android 12+ (API 31+) NSD requires location permission:
// <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
// Or for just discovery:
// <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION"/>

class GameViewModel(private val applicationContext: Context) : ViewModel() {

    // --- Game State ---
    private val _state = MutableStateFlow(createInitialEmptyGameState())
    val state: StateFlow<GameState> = _state.asStateFlow()

    // --- Navigation/Events ---
    private val _navigateToResultScreen = MutableSharedFlow<RoundEvaluator.RoundResult>()
    val navigateToResultScreen: SharedFlow<RoundEvaluator.RoundResult> =
            _navigateToResultScreen.asSharedFlow()

    private val _showError = MutableSharedFlow<String>()
    val showError: SharedFlow<String> = _showError.asSharedFlow()

    // --- Game/Network Setup ---
    var isHost: Boolean = false
        private set
    var requiredPlayerCount: Int = 4
        private set
    var localPlayerId: Int = -1
        private set

    private val _connectedPlayersCount = MutableStateFlow(0)
    val connectedPlayersCount: StateFlow<Int> = _connectedPlayersCount

    private val _gameStarted = MutableStateFlow(false)
    val gameStarted: StateFlow<Boolean> = _gameStarted

    // --- Networking (Host) ---
    private var serverSocket: ServerSocket? = null
    private var isServerRunning = false
    private val clientSockets = ConcurrentHashMap<Int, Socket>()
    private val clientWriters = ConcurrentHashMap<Int, PrintWriter>()
    private val clientReaders = ConcurrentHashMap<Int, BufferedReader>()
    private val clientJobs = ConcurrentHashMap<Int, Job>()

    // --- Networking (Client) ---
    private var clientSocket: Socket? = null
    private var clientWriter: PrintWriter? = null
    private var clientReader: BufferedReader? = null
    private var clientReaderJob: Job? = null
    private var isConnectedToServer = false

    // --- Network Discovery (NSD) ---
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    // private var resolveListener: NsdManager.ResolveListener? = null // Less needed if resolving
    // inline
    private val SERVICE_TYPE = "_mindikot._tcp"
    private var serviceName = "MindikotGame" // Base name, will be made unique
    private var nsdServiceNameRegistered: String? = null // Actual name registered

    private val _discoveredHosts = MutableStateFlow<List<NsdServiceInfo>>(emptyList())
    val discoveredHosts: StateFlow<List<NsdServiceInfo>> = _discoveredHosts.asStateFlow()

    private val _hostIpAddress = MutableStateFlow<String?>(null) // For host display
    val hostIpAddress: StateFlow<String?> = _hostIpAddress.asStateFlow()

    // --- Serialization ---
    private val gson = Gson()

    // --- Logging ---
    private fun log(message: String, tag: String = "GameViewModel") {
        println("[$tag] $message")
    }
    private fun logError(message: String, error: Throwable? = null) {
        val errorMsg = error?.message?.let { ": $it" } ?: ""
        println("[GameViewModel ERROR] $message$errorMsg")
        // Optionally log full stack trace for debugging
        // error?.printStackTrace()
    }

    // ========================================================================
    // HOST FUNCTIONS
    // ========================================================================

    /** HOST: Initializes the game settings (player slots, mode) */
    fun initializeGameSettings(
            playerName: String,
            mode: GameMode,
            host: Boolean = true,
            playersNeeded: Int = 4
    ) {
        log(
                "Initializing game settings as Host. Name: $playerName, Mode: $mode, Players: $playersNeeded"
        )
        isHost = host
        requiredPlayerCount = playersNeeded
        localPlayerId = 0 // Host is always Player 0

        val players =
                (0 until playersNeeded).map { i ->
                    Player(
                            id = i,
                            name = if (i == 0) playerName else "Waiting...",
                            teamId = (i % 2) + 1,
                            hand = mutableListOf()
                    )
                }
        val teams =
                listOf(
                        Team(id = 1, players = players.filter { it.teamId == 1 }),
                        Team(id = 2, players = players.filter { it.teamId == 2 })
                )

        _state.value =
                GameState(
                        players = players,
                        teams = teams,
                        gameMode = mode,
                        tricksWon = mutableMapOf(1 to 0, 2 to 0)
                        // Other fields default or are null initially
                        )
        _connectedPlayersCount.value = 1 // Host is connected
        log("Initial GameState created for host setup.")
    }

    /** HOST: Starts ServerSocket and NSD Registration */
    fun startServerAndDiscovery(port: Int = 0) { // Port 0 lets OS pick free port
        if (isServerRunning || !isHost) {
            log("Server already running or not host. Aborting start.")
            return
        }
        log("Attempting to start server and NSD registration...")
        isServerRunning = true // Set flag early
        viewModelScope.launch(Dispatchers.IO) {
            var serverStarted = false
            var nsdRegistered = false
            try {
                // 1. Start Server Socket
                serverSocket = ServerSocket(port)
                val actualPort = serverSocket!!.localPort
                servicePort = actualPort // Store the chosen port
                log("Server socket started successfully on port $actualPort.")
                serverStarted = true

                // 2. Get Host IP for display (best effort)
                val localIp = getLocalIpAddress()
                withContext(Dispatchers.Main) { _hostIpAddress.value = localIp?.hostAddress }
                log("Host IP for display: ${localIp?.hostAddress ?: "Not Found"}")

                // 3. Register NSD Service
                if (registerNsdService(actualPort)) {
                    nsdRegistered = true
                } else {
                    // Registration failed, stop the server socket
                    throw Exception("NSD Registration Failed")
                }

                log("Server and NSD active. Waiting for ${requiredPlayerCount - 1} players...")

                // 4. Accept Client Connections Loop
                while (clientSockets.size < requiredPlayerCount - 1 &&
                        isServerRunning &&
                        isActive) {
                    val socket = serverSocket?.accept() ?: break // Wait for connection
                    val currentClientCount = clientSockets.size
                    val assignedPlayerId = currentClientCount + 1

                    log("Client connected, assigning Player ID $assignedPlayerId")

                    try {
                        val writer = PrintWriter(socket.getOutputStream(), true)
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                        clientSockets[assignedPlayerId] = socket
                        clientWriters[assignedPlayerId] = writer
                        clientReaders[assignedPlayerId] = reader

                        // Send ID first, then current lobby state
                        sendMessageToClient(
                                assignedPlayerId,
                                NetworkMessage(MessageType.ASSIGN_ID, assignedPlayerId)
                        )
                        // Delay slightly to ensure client processes ID before state? Might not be
                        // needed.
                        // delay(50)
                        sendMessageToClient(
                                assignedPlayerId,
                                NetworkMessage(MessageType.GAME_STATE_UPDATE, _state.value)
                        )

                        listenToClient(assignedPlayerId, reader) // Start listening

                        withContext(Dispatchers.Main.immediate) {
                            _connectedPlayersCount.value = clientSockets.size + 1
                        }
                        log(
                                "Player ID $assignedPlayerId assigned. Connected: ${_connectedPlayersCount.value}/${requiredPlayerCount}"
                        )

                        if (_connectedPlayersCount.value == requiredPlayerCount) {
                            log("All players connected!")
                            prepareAndBroadcastInitialState() // Deal cards and start game
                            // Don't break here, let loop finish naturally if isServerRunning
                            // becomes false
                        }
                    } catch (e: Exception) {
                        logError("Error during client setup (Player $assignedPlayerId)", e)
                        runCatching { socket.close() } // Close this specific socket on error
                    }
                } // End of accept loop
            } catch (e: Exception) {
                if (isServerRunning) { // Only log/show error if not intentionally stopped
                    logError("Server/NSD start failed or accept loop error", e)
                    withContext(Dispatchers.Main) {
                        _showError.emit("Error starting host: ${e.message}")
                    }
                }
                // Cleanup needed if partial start occurred
                withContext(Dispatchers.Main) { stopServerAndDiscovery() }
            } finally {
                log("Server accept loop finished. isServerRunning=$isServerRunning")
                // If loop exited but server thought it was running, ensure cleanup
                if (isServerRunning) {
                    // This might happen if required players connect and break isn't used,
                    // or if serverSocket?.accept() throws after isServerRunning check.
                    // Consider if stopServerAndDiscovery is needed here.
                    // For now, let stopServerAndDiscovery handle final cleanup.
                }
            }
        }
    }

    /**
     * HOST: Registers the game service using NSD. Returns true on success request, false on
     * immediate failure.
     */
    private fun registerNsdService(port: Int): Boolean {
        // Cleanup previous listener if any
        if (registrationListener != null) {
            try {
                nsdManager?.unregisterService(registrationListener)
            } catch (e: Exception) {}
            registrationListener = null
        }

        nsdManager = applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager?
        if (nsdManager == null) {
            logError("NSD Manager service not available.")
            viewModelScope.launch { _showError.emit("Network Discovery service unavailable.") }
            return false
        }

        registrationListener =
                object : NsdManager.RegistrationListener {
                    override fun onServiceRegistered(nsdServiceInfo: NsdServiceInfo) {
                        nsdServiceNameRegistered =
                                nsdServiceInfo.serviceName // Store the actual registered name
                        log("NSD Service registered: $nsdServiceNameRegistered on port $port")
                    }
                    override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        logError(
                                "NSD registration failed for ${serviceInfo.serviceName}: Error $errorCode"
                        )
                        viewModelScope.launch {
                            _showError.emit("Failed to advertise game (Error $errorCode)")
                        }
                        nsdServiceNameRegistered = null
                        // Consider stopping the server if NSD fails? Depends on requirements.
                    }
                    override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                        log("NSD Service unregistered: ${arg0.serviceName}")
                        nsdServiceNameRegistered = null
                    }
                    override fun onUnregistrationFailed(
                            serviceInfo: NsdServiceInfo,
                            errorCode: Int
                    ) {
                        logError(
                                "NSD unregistration failed for ${serviceInfo.serviceName}: Error $errorCode"
                        )
                        // May happen if already unregistered or network issues
                    }
                }

        // Create unique service name
        val baseName = "Mindikot" // Can use host player name if desired, sanitize it first
        val uniqueName = "${baseName}_${(1000..9999).random()}"
        val serviceInfo =
                NsdServiceInfo().apply {
                    setServiceName(uniqueName)
                    setServiceType(SERVICE_TYPE)
                    setPort(port)
                    // Optional: Add attributes for clients to see in discovery (TXTPacket)
                    // setAttribute("version", "1.0")
                    // setAttribute("mode", _state.value.gameMode.name)
                    // setAttribute("players",
                    // "${_connectedPlayersCount.value}/${requiredPlayerCount}")
                }

        log("Attempting to register NSD service: $uniqueName")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                nsdManager?.registerService(
                        serviceInfo,
                        NsdManager.PROTOCOL_DNS_SD,
                        registrationListener
                )
                return true // Request submitted
            } else {
                logError("NSD requires API level 16+")
                viewModelScope.launch { _showError.emit("Network discovery requires Android 4.1+") }
                return false
            }
        } catch (e: Exception) {
            logError("Exception calling registerService", e)
            viewModelScope.launch { _showError.emit("Error starting network advertisement.") }
            return false
        }
    }

    /** HOST: Stops the ServerSocket and unregisters NSD service. */
    fun stopServerAndDiscovery() {
        if (!isHost) return
        if (!isServerRunning && serverSocket == null && registrationListener == null) {
            log("stopServerAndDiscovery called but server/NSD not active.")
            return // Already stopped or never started
        }
        log("Stopping server and NSD...")
        isServerRunning = false // Signal loops/jobs to stop

        // 1. Unregister NSD
        if (nsdManager != null && registrationListener != null) {
            log("Unregistering NSD service: $nsdServiceNameRegistered")
            try {
                nsdManager?.unregisterService(registrationListener)
            } catch (e: IllegalArgumentException) {
                log("NSD Listener likely already unregistered: ${e.message}")
            } catch (e: Exception) {
                logError("Error unregistering NSD service", e)
            } finally {
                registrationListener = null
            }
        } else {
            log("NSD Manager or Listener was null, skipping unregister.")
        }

        // 2. Close Server Socket (this will interrupt the accept() call)
        runCatching { serverSocket?.close() }.onSuccess { log("Server socket closed.") }.onFailure {
            logError("Error closing server socket", it)
        }
        serverSocket = null

        // 3. Cancel all client listener jobs
        clientJobs.values.forEach { it.cancel("Server stopping") }
        clientJobs.clear()

        // 4. Close all client connections
        val clientIds = clientSockets.keys.toList() // Avoid concurrent mod
        clientIds.forEach { id ->
            // Use removeClient for full cleanup of each
            removeClient(id)
        }
        // Ensure maps are clear
        clientSockets.clear()
        clientWriters.clear()
        clientReaders.clear()

        // Reset host-specific state
        _connectedPlayersCount.value = 0
        _gameStarted.value = false
        _hostIpAddress.value = null
        _state.value = createInitialEmptyGameState() // Reset game state

        log("Server stopped, NSD unregistered, connections closed.")
    }

    // --- Other Host Functions (listenToClient, handleClientMessage, prepareAndBroadcast, etc. -
    // KEEP AS IS) ---
    // ... (Assume these are mostly correct from previous step, ensure logging/error handling is
    // adequate)
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
                                logError("Error handling message from Player $playerId", e)
                            }
                        }
                    } catch (e: Exception) {
                        if (isActive) { // Avoid logging errors during cancellation
                            logError("Error reading from Player $playerId socket", e)
                        }
                    } finally {
                        log("Listener stopped for Player $playerId.")
                        // Ensure cleanup happens on the Main thread safely
                        withContext(Dispatchers.Main) { removeClient(playerId) }
                    }
                }
        clientJobs[playerId]?.invokeOnCompletion { throwable ->
            if (throwable != null && throwable !is CancellationException) {
                logError("Listener job for Player $playerId completed with error", throwable)
            } else {
                log("Listener job for Player $playerId completed normally or cancelled.")
            }
            // Ensure cleanup happens on job completion too
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
                        // Deserialize based on expected type - Requires robust handling
                        val actionData: Any? =
                                when (expectedType) {
                                    // Need more robust deserialization (e.g., check data type
                                    // before parsing)
                                    InputType.PLAY_CARD,
                                    InputType.CHOOSE_TRUMP_SUIT ->
                                            try {
                                                gson.fromJson(
                                                        gson.toJson(message.data),
                                                        Card::class.java
                                                )
                                            } catch (e: Exception) {
                                                null
                                            }
                                    InputType.REVEAL_OR_PASS ->
                                            try {
                                                gson.fromJson(
                                                        gson.toJson(message.data),
                                                        GameEngine.Decision::class.java
                                                )
                                            } catch (e: Exception) {
                                                null
                                            }
                                    null -> null // Error case
                                }

                        if (actionData != null) {
                            processGameInput(playerId, actionData) // Pass Player ID for context
                        } else {
                            logError(
                                    "Failed to parse PLAYER_ACTION data for expected type $expectedType from Player $playerId"
                            )
                            sendMessageToClient(
                                    playerId,
                                    NetworkMessage(MessageType.ERROR, "Invalid action data format.")
                            )
                        }
                    } catch (e: Exception) {
                        logError("Error deserializing PLAYER_ACTION data from Player $playerId", e)
                        sendMessageToClient(
                                playerId,
                                NetworkMessage(MessageType.ERROR, "Error processing your action.")
                        )
                    }
                } else {
                    log(
                            "Received action from Player $playerId but it's not their turn (expected ${_state.value.awaitingInputFromPlayerIndex})."
                    )
                    sendMessageToClient(
                            playerId,
                            NetworkMessage(MessageType.ERROR, "Not your turn")
                    )
                }
            }
            MessageType.PLAYER_NAME -> {
                // Ensure data is a string before updating
                val name = message.data as? String
                if (name != null) {
                    updatePlayerName(playerId, name) // Update name and broadcast state
                } else {
                    logError(
                            "Received invalid PLAYER_NAME data from Player $playerId: ${message.data}"
                    )
                }
            }
            else -> log("Received unhandled message type: ${message.type} from Player $playerId")
        }
    }

    /** HOST: Deals cards, sets hidden card (Mode B), updates state, and broadcasts. */
    private fun prepareAndBroadcastInitialState() {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            log("Preparing initial game state for ${requiredPlayerCount} players...")
            val currentPlayers = _state.value.players
            if (currentPlayers.size != requiredPlayerCount ||
                            currentPlayers.any {
                                it.name == "Waiting..." || it.name == "[Disconnected]"
                            }
            ) {
                logError("Cannot prepare initial state: Incorrect number or incomplete players.")
                // Maybe send error to clients or host UI
                return@launch
            }

            val deck = DeckGenerator.generateDeck(requiredPlayerCount)
            var hiddenCard: Card? = null

            if (_state.value.gameMode == GameMode.FIRST_CARD_HIDDEN) {
                if (deck.isNotEmpty()) {
                    hiddenCard = deck.removeAt(0)
                    log("Hidden card set (Mode B): ${hiddenCard.suit}")
                } else {
                    /* Error handling */
                    return@launch
                }
            }

            val updatedPlayers = currentPlayers.toMutableList()
            val cardsPerPlayer = deck.size / requiredPlayerCount
            if (deck.size % requiredPlayerCount != 0) {
                logError("Deck size not evenly divisible after hidden card!")
            }

            for (i in 0 until requiredPlayerCount) {
                val handCards = deck.take(cardsPerPlayer).toMutableList()
                if (i < updatedPlayers.size) {
                    updatedPlayers[i] = updatedPlayers[i].copy(hand = handCards)
                    deck.removeAll(handCards.toSet())
                } else {
                    /* Error handling */
                }
            }
            log("Cards dealt. Remaining deck: ${deck.size}")

            var initialState =
                    _state.value.copy(
                            players = updatedPlayers,
                            hiddenCard = hiddenCard,
                            tricksWon = mutableMapOf(1 to 0, 2 to 0),
                            currentTrickPlays = mutableListOf(),
                            trumpSuit = null,
                            trumpRevealed = false,
                            currentLeaderIndex = 0
                    )
            initialState = GameEngine.requestInput(initialState, initialState.currentLeaderIndex)
            _state.value = initialState

            broadcastGameState(_state.value)
            log("Initial GameState broadcast.")
            _gameStarted.value = true // Set game started flag
        }
    }

    /** HOST: Broadcasts the GameState to all connected clients. */
    private fun broadcastGameState(gameState: GameState) {
        if (!isHost) return
        val message = NetworkMessage(MessageType.GAME_STATE_UPDATE, gameState)
        log("Broadcasting GameState to ${clientWriters.size} clients...")
        clientWriters.keys.toList().forEach { id -> // Iterate safely
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
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val messageJson = gson.toJson(message)
                synchronized(writer) { writer.println(messageJson) } // Synchronize write access
                if (writer.checkError()) {
                    throw Exception("PrintWriter error after write.")
                }
                // log("Sent to Player $playerId: ${message.type}") // Reduce verbose logging if
                // needed
            } catch (e: Exception) {
                logError("Error sending message to Player $playerId", e)
                // Assume client disconnected on send error
                withContext(Dispatchers.Main) { removeClient(playerId) }
            }
        }
    }

    /** HOST: Cleans up resources associated with a disconnected or removed client. */
    private fun removeClient(playerId: Int) {
        // Use Main.immediate if possibly called from background thread completion handlers
        viewModelScope.launch(Dispatchers.Main.immediate) {
            if (!clientSockets.containsKey(playerId)) return@launch // Already removed

            log("Removing client Player $playerId...")
            clientJobs[playerId]?.cancel("Client removed")
            clientJobs.remove(playerId)

            runCatching { clientWriters[playerId]?.close() }.onFailure {
                logError("Error closing writer for $playerId", it)
            }
            runCatching { clientReaders[playerId]?.close() }.onFailure {
                logError("Error closing reader for $playerId", it)
            }
            runCatching { clientSockets[playerId]?.close() }.onFailure {
                logError("Error closing socket for $playerId", it)
            }

            clientWriters.remove(playerId)
            clientReaders.remove(playerId)
            clientSockets.remove(playerId)

            _connectedPlayersCount.value = clientSockets.size + 1

            log(
                    "Player $playerId removed. Connected: ${_connectedPlayersCount.value}/${requiredPlayerCount}"
            )

            if (_gameStarted.value) {
                // Handle game interruption more gracefully?
                // For now, just inform everyone and maybe stop the game/server
                val playerName =
                        _state.value.players.find { it.id == playerId }?.name ?: "Player $playerId"
                _showError.emit("$playerName disconnected. Game interrupted.")
                broadcastGameState(
                        _state.value.copy(
                                players =
                                        _state.value.players.map { // Mark player visually
                                            if (it.id == playerId)
                                                    it.copy(
                                                            name = "$playerName [LEFT]",
                                                            hand = mutableListOf()
                                                    )
                                            else it
                                        }
                        )
                )
                // Consider stopping the game fully?
                // stopServerAndDiscovery()
            } else {
                // Still in lobby
                _state.update { state ->
                    val updatedPlayers =
                            state.players.map {
                                if (it.id == playerId)
                                        it.copy(name = "[Disconnected]", hand = mutableListOf())
                                else it
                            }
                    state.copy(players = updatedPlayers)
                }
                broadcastGameState(_state.value)
            }
        }
    }

    // ========================================================================
    // CLIENT FUNCTIONS
    // ========================================================================

    /** CLIENT: Starts discovering host services on the network */
    fun startNsdDiscovery() {
        if (isHost) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Check permission for Android 12+
            if (ContextCompat.checkSelfPermission(
                            applicationContext,
                            Manifest.permission.ACCESS_FINE_LOCATION
                    ) != PackageManager.PERMISSION_GRANTED
            ) {
                logError(
                        "Cannot start NSD discovery: ACCESS_FINE_LOCATION permission required on Android 12+."
                )
                viewModelScope.launch {
                    _showError.emit("Location permission needed to find games.")
                }
                // UI should prompt for permission
                return
            }
        }

        log("Client: Starting NSD discovery...")
        stopNsdDiscovery() // Stop previous discovery first
        _discoveredHosts.value = emptyList() // Clear previous results

        nsdManager = applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager?
        if (nsdManager == null) {
            logError("NSD Manager service not available.")
            viewModelScope.launch { _showError.emit("Network Discovery service unavailable.") }
            return
        }

        discoveryListener =
                object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(regType: String) {
                        log("NSD discovery started.")
                    }
                    override fun onServiceFound(service: NsdServiceInfo) {
                        log(
                                "NSD service found: ${service.serviceName}, type: ${service.serviceType}"
                        )
                        if (service.serviceType == SERVICE_TYPE &&
                                        service.serviceName != nsdServiceNameRegistered
                        ) { // Avoid discovering self if somehow registered
                            log("Attempting to resolve service: ${service.serviceName}")
                            resolveNsdService(service)
                        }
                    }
                    override fun onServiceLost(service: NsdServiceInfo) {
                        log("NSD service lost: ${service.serviceName}")
                        _discoveredHosts.update { list ->
                            list.filterNot { it.serviceName == service.serviceName }
                        }
                    }
                    override fun onDiscoveryStopped(serviceType: String) {
                        log("NSD discovery stopped.")
                    }
                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                        logError("NSD discovery start failed: Error code $errorCode")
                        viewModelScope.launch {
                            _showError.emit("Failed to search for games (Error $errorCode)")
                        }
                        stopNsdDiscovery() // Cleanup on failure
                    }
                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                        logError("NSD discovery stop failed: Error code $errorCode")
                    }
                }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                nsdManager?.discoverServices(
                        SERVICE_TYPE,
                        NsdManager.PROTOCOL_DNS_SD,
                        discoveryListener
                )
            } else {
                /* Handle older API error */
            }
        } catch (e: Exception) {
            logError("Exception calling discoverServices", e)
            viewModelScope.launch { _showError.emit("Error starting network search.") }
        }
    }

    /** CLIENT: Resolves a discovered service to get host and port */
    private fun resolveNsdService(serviceInfo: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN || nsdManager == null) return

        // Check if already resolved or resolving this specific service name to avoid spamming
        // resolves
        if (_discoveredHosts.value.any {
                    it.serviceName == serviceInfo.serviceName && it.host != null
                }
        ) {
            log("Service ${serviceInfo.serviceName} already resolved.")
            return
        }
        log("Resolving NSD service: ${serviceInfo.serviceName}")

        // Use a local listener instance
        val listener =
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(
                            failedServiceInfo: NsdServiceInfo,
                            errorCode: Int
                    ) {
                        logError(
                                "NSD resolve failed for ${failedServiceInfo.serviceName}: Error code $errorCode"
                        )
                        // Optionally remove from a "resolving" list if tracking attempts
                    }
                    override fun onServiceResolved(resolvedServiceInfo: NsdServiceInfo) {
                        log(
                                "NSD service resolved: ${resolvedServiceInfo.serviceName} at ${resolvedServiceInfo.host}:${resolvedServiceInfo.port}"
                        )
                        // Update the list on the Main thread
                        viewModelScope.launch(Dispatchers.Main) {
                            _discoveredHosts.update { list ->
                                val existingIndex =
                                        list.indexOfFirst {
                                            it.serviceName == resolvedServiceInfo.serviceName
                                        }
                                if (existingIndex != -1) {
                                    // Update existing entry
                                    list.toMutableList().apply {
                                        set(existingIndex, resolvedServiceInfo)
                                    }
                                } else {
                                    // Add new entry
                                    list + resolvedServiceInfo
                                }
                            }
                        }
                    }
                }

        try {
            nsdManager?.resolveService(serviceInfo, listener)
        } catch (e: Exception) {
            logError("Exception calling resolveService for ${serviceInfo.serviceName}", e)
        }
    }

    /** CLIENT: Stops NSD discovery */
    fun stopNsdDiscovery() {
        if (isHost || nsdManager == null || discoveryListener == null) return
        log("Client: Stopping NSD discovery...")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                nsdManager?.stopServiceDiscovery(discoveryListener)
            }
        } catch (e: IllegalArgumentException) {
            log("NSD Discovery likely already stopped: ${e.message}")
        } catch (e: Exception) {
            logError("Error stopping NSD discovery", e)
        } finally {
            discoveryListener = null // Release listener
            // Don't nullify nsdManager here, might be needed for other operations or restart
            _discoveredHosts.value = emptyList() // Clear discovered hosts when stopping search
        }
    }

    /** CLIENT: Connects to a selected discovered host */
    fun connectToDiscoveredHost(serviceInfo: NsdServiceInfo, playerName: String) {
        val hostAddress = serviceInfo.host?.hostAddress // Resolved IP address
        val port = serviceInfo.port // Resolved port

        if (hostAddress != null && port > 0) {
            log(
                    "Client: Connecting to selected host: ${serviceInfo.serviceName} ($hostAddress:$port)"
            )
            connectToServer(hostAddress, port, playerName) // Use existing connectToServer logic
        } else {
            logError(
                    "Client: Cannot connect, resolved service info is invalid (missing host/port): $serviceInfo"
            )
            viewModelScope.launch {
                _showError.emit(
                        "Failed to get connection details for '${serviceInfo.serviceName}'. Please refresh."
                )
            }
        }
    }

    /** CLIENT: Connects to the game host using IP/Port */
    fun connectToServer(hostAddress: String, port: Int = 8888, playerName: String) {
        if (isConnectedToServer || isHost) {
            log("Client: Already connected or is host. Aborting connection.")
            return
        }
        log("Client: Attempting connection to $hostAddress:$port...")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Ensure prior connection attempt is fully cleaned up
                disconnectFromServer()
                delay(100) // Brief pause before new attempt

                clientSocket = Socket(hostAddress, port)
                clientWriter = PrintWriter(clientSocket!!.getOutputStream(), true)
                clientReader = BufferedReader(InputStreamReader(clientSocket!!.getInputStream()))
                isConnectedToServer = true // Set flag *after* successful setup
                log("Client: Connected successfully to $hostAddress:$port.")

                listenToServer() // Start listening for server messages
                sendMessageToServer(
                        NetworkMessage(MessageType.PLAYER_NAME, playerName)
                ) // Send name

                // UI should navigate to waiting screen here

            } catch (e: Exception) {
                logError("Client: Connection to $hostAddress:$port failed", e)
                isConnectedToServer = false
                withContext(Dispatchers.Main) { _showError.emit("Connection failed: ${e.message}") }
                disconnectFromServer() // Cleanup on failure
            }
        }
    }

    /** CLIENT: Listens for messages from the server */
    private fun listenToServer() {
        clientReaderJob?.cancel() // Ensure only one listener
        clientReaderJob =
                viewModelScope.launch(Dispatchers.IO) {
                    log("Client: Listener started.")
                    try {
                        while (isActive) {
                            val messageJson =
                                    clientReader?.readLine()
                                            ?: break // null means connection closed
                            // log("Client: Received raw: $messageJson") // Verbose logging
                            try {
                                val message = gson.fromJson(messageJson, NetworkMessage::class.java)
                                withContext(Dispatchers.Main.immediate) {
                                    handleServerMessage(message)
                                }
                            } catch (e: Exception) {
                                /* Handle parse/processing errors */
                                logError("Client: Error handling message", e)
                            }
                        }
                    } catch (e: Exception) {
                        if (isActive) {
                            logError("Client: Socket read error", e)
                        }
                    } finally {
                        log("Client: Listener stopped.")
                        withContext(Dispatchers.Main) { disconnectFromServer() } // Ensure cleanup
                    }
                }
        clientReaderJob?.invokeOnCompletion { /* Optional logging */}
    }

    /** CLIENT: Handles messages received from the server */
    private fun handleServerMessage(message: NetworkMessage) {
        log("Client: Handling message: ${message.type}")
        when (message.type) {
            MessageType.ASSIGN_ID -> {
                val id = (message.data as? Double)?.toInt() ?: -1
                if (id != -1 && localPlayerId == -1) {
                    localPlayerId = id
                    log("Client: Assigned Player ID: $localPlayerId")
                } else {
                    /* Handle error/conflict */
                }
            }
            MessageType.GAME_STATE_UPDATE -> {
                try {
                    val gameStateJson = gson.toJson(message.data)
                    val updatedState = gson.fromJson(gameStateJson, GameState::class.java)
                    if (updatedState.players.isEmpty() && _state.value.players.isNotEmpty()) {
                        logError("Client: Received potentially invalid empty GameState, ignoring.")
                        return // Avoid resetting state unexpectedly
                    }
                    _state.value = updatedState
                    _connectedPlayersCount.value =
                            updatedState.players.count {
                                it.name != "Waiting..." && it.name != "[Disconnected]"
                            }
                    log(
                            "Client: GameState updated. Awaiting: ${updatedState.awaitingInputFromPlayerIndex}"
                    )
                    val myHand = updatedState.players.find { it.id == localPlayerId }?.hand
                    if (!_gameStarted.value && myHand?.isNotEmpty() == true) {
                        _gameStarted.value = true
                        log("Client: Game Started (received hand).")
                    }
                } catch (e: Exception) {
                    logError("Client: Error deserializing GAME_STATE_UPDATE", e)
                }
            }
            MessageType.ERROR -> {
                val errorMsg = message.data as? String ?: "Unknown server error"
                logError("Client: Received error from server: $errorMsg")
                viewModelScope.launch { _showError.emit(errorMsg) }
            }
            // Handle other specific messages like ROUND_RESULT if added
            else -> log("Client: Received unhandled message type: ${message.type}")
        }
    }

    /** CLIENT: Sends a message to the host server */
    private fun sendMessageToServer(message: NetworkMessage) {
        if (!isConnectedToServer || isHost || clientWriter == null) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val messageJson = gson.toJson(message)
                synchronized(clientWriter!!) { clientWriter?.println(messageJson) }
                if (clientWriter?.checkError() == true) {
                    throw Exception("PrintWriter error")
                }
                // log("Client: Sent message: ${message.type}") // Less verbose potentially
            } catch (e: Exception) {
                logError("Client: Error sending message", e)
                withContext(Dispatchers.Main) {
                    _showError.emit("Connection error. Failed to send.")
                }
                // Consider disconnecting on send error
                // disconnectFromServer()
            }
        }
    }

    /** CLIENT: Disconnects from the server and cleans up resources */
    fun disconnectFromServer() {
        if (isHost || (!isConnectedToServer && clientSocket == null))
                return // Already disconnected or host

        log("Client: Disconnecting...")
        isConnectedToServer = false // Set flag first
        clientReaderJob?.cancel("Client disconnecting") // Cancel listener
        clientReaderJob = null

        // Close streams and socket safely
        runCatching { clientWriter?.close() }.onFailure {
            logError("Error closing client writer", it)
        }
        runCatching { clientReader?.close() }.onFailure {
            logError("Error closing client reader", it)
        }
        runCatching { clientSocket?.close() }.onFailure {
            logError("Error closing client socket", it)
        }

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

    /** HOST ONLY: Processes player input using the GameEngine and broadcasts the result */
    private fun processGameInput(actingPlayerId: Int, playerInput: Any) {
        if (!isHost) return

        viewModelScope.launch(Dispatchers.Main.immediate) {
            val currentState = _state.value
            if (currentState.awaitingInputFromPlayerIndex != actingPlayerId) {
                logError(
                        "Input processed for wrong player. Expected: ${currentState.awaitingInputFromPlayerIndex}, Got: $actingPlayerId"
                )
                sendMessageToClient(
                        actingPlayerId,
                        NetworkMessage(MessageType.ERROR, "Not your turn")
                )
                return@launch
            }

            log("Host: Processing input from Player $actingPlayerId: $playerInput")
            try {
                // IMPORTANT: GameEngine modifies the state object passed to it.
                // Consider passing a deep copy if you need to revert on error,
                // but for now, we let it modify directly.
                GameEngine.processPlayerInput(currentState, playerInput)

                // Update the StateFlow with the modified state
                _state.value = currentState // Trigger UI update

                val roundEnded =
                        currentState.players.firstOrNull()?.hand?.isEmpty() == true &&
                                currentState.currentTrickPlays.isEmpty()

                if (roundEnded) {
                    log("Host: Round Ended. Evaluating...")
                    val result = RoundEvaluator.evaluateRound(currentState)
                    log(
                            "Host: Round Result: Winner=${result.winningTeam?.id ?: "Draw"}, Kot=${result.isKot}"
                    )
                    // TODO: Update game scores

                    broadcastGameState(currentState) // Broadcast final round state
                    delay(200) // Allow clients to receive final state
                    _navigateToResultScreen.emit(result) // Notify host UI

                    // TODO: Handle next round start logic
                } else {
                    // Round continues, broadcast updated state
                    broadcastGameState(currentState)
                }
            } catch (e: IllegalStateException) {
                logError(
                        "Host: Invalid move detected by GameEngine for Player $actingPlayerId: ${e.message}"
                )
                sendMessageToClient(
                        actingPlayerId,
                        NetworkMessage(MessageType.ERROR, "Invalid Move: ${e.message}")
                )
                // Re-request input from the same player based on the CURRENT state
                _state.value = GameEngine.requestInput(currentState, actingPlayerId)
                broadcastGameState(_state.value)
            } catch (e: Exception) {
                logError(
                        "Host: Unexpected error processing game input for Player $actingPlayerId",
                        e
                )
                _showError.emit("Internal Server Error.")
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
            /* Error handling */
            return
        }
        if (expectedInput != InputType.PLAY_CARD && expectedInput != InputType.CHOOSE_TRUMP_SUIT) {
            /* Error handling */
            return
        }
        if (currentState.players.find { it.id == localPlayerId }?.hand?.contains(card) != true) {
            /* Error handling */
            return
        }

        // Basic client-side validation (is card valid based on known rules?) Optional but good UX
        val validMoves =
                GameEngine.determineValidMoves(
                        currentState.players.find { it.id == localPlayerId }?.hand ?: emptyList(),
                        currentState.currentTrickPlays,
                        currentState.trumpSuit,
                        currentState.trumpRevealed
                )
        if (!validMoves.contains(card)) {
            logError("UI Action: Attempted to play invalid card $card. Valid: $validMoves")
            viewModelScope.launch { _showError.emit("Invalid move.") }
            return
        }

        if (isHost) {
            processGameInput(localPlayerId, card)
        } else {
            sendMessageToServer(NetworkMessage(MessageType.PLAYER_ACTION, card))
        }
    }

    /** Called when the local player chooses Reveal or Pass (Mode B) */
    fun onRevealOrPass(decision: GameEngine.Decision) {
        log("UI Action: Reveal/Pass decision: $decision by Local Player $localPlayerId")
        val currentState = _state.value
        if (currentState.awaitingInputFromPlayerIndex != localPlayerId ||
                        currentState.requiredInputType != InputType.REVEAL_OR_PASS
        ) {
            /* Error handling */ return
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
                gameMode = GameMode.CHOOSE_WHEN_EMPTY,
                tricksWon = mutableMapOf()
        )
    }

    /** HOST: Updates player name in the authoritative state and broadcasts the change */
    private fun updatePlayerName(playerId: Int, name: String) {
        if (!isHost) return
        log("Host: Updating Player $playerId name to '$name'")
        var nameChanged = false
        _state.update { currentState ->
            val currentName = currentState.players.find { it.id == playerId }?.name
            if (currentName != name) {
                nameChanged = true
                val updatedPlayers =
                        currentState.players.map {
                            if (it.id == playerId) it.copy(name = name) else it
                        }
                currentState.copy(players = updatedPlayers)
            } else {
                currentState // No change needed
            }
        }
        if (nameChanged) {
            broadcastGameState(_state.value) // Broadcast if changed
        }
    }

    /** Gets the local IP address (needs refinement for robustness) */
    private fun getLocalIpAddress(): InetAddress? {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces().toList()
            interfaces
                    .flatMap { intf ->
                        intf.inetAddresses.toList().filter { addr ->
                            !addr.isLoopbackAddress && addr is java.net.Inet4Address
                        }
                    }
                    .firstOrNull()
        } catch (e: Exception) {
            logError("Could not determine local IP address", e)
            null
        }
    }

    /** Called when ViewModel is cleared - ensures network cleanup */
    override fun onCleared() {
        log("GameViewModel Cleared.")
        if (isHost) {
            stopServerAndDiscovery()
        } else {
            stopNsdDiscovery()
            disconnectFromServer()
        }
        // Release NSD Manager reference if held
        // nsdManager = null // Consider if needed, might interfere if discovery restarts quickly
        super.onCleared()
    }
}

// ========================================================================
// VIEWMODEL FACTORY (Required for injecting Context)
// ========================================================================
class GameViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(GameViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST") return GameViewModel(context.applicationContext) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

// ========================================================================
// DATA CLASSES FOR NETWORKING
// ========================================================================

/** Defines the type of message being sent over the network. */
enum class MessageType {
    ASSIGN_ID, // Server -> Client: Your assigned player ID {data: Int}
    GAME_STATE_UPDATE, // Server -> Client: The current full GameState {data: GameState}
    PLAYER_ACTION, // Client -> Server: Player performed an action {data: Card or
    // GameEngine.Decision}
    // REQUEST_INPUT,      // Server -> Client: It's your turn (optional) {data: PlayerID}
    PLAYER_NAME, // Client -> Server: Sending player's chosen name {data: String}
    ERROR // Server -> Client: An error occurred {data: String}
    // Consider: ROUND_RESULT, GAME_OVER, PING, PONG etc.
}

/** Represents a message sent between the host and clients. */
data class NetworkMessage(
        val type: MessageType,
        /**
         * Data payload. Needs careful serialization/deserialization based on 'type'. Use specific
         * data classes or robust JSON handling (e.g., kotlinx.serialization polymorphic)
         */
        val data: Any? = null
)
