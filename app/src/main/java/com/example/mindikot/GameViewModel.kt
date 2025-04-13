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
    private var servicePort: Int = 0 // *** DECLARED servicePort property ***
        private set
    var isServerRunning = false
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

    private var nsdManager: NsdManager? = null
    // Keep registrationListener for host
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val SERVICE_TYPE = "_mindikot._tcp"
    private var nsdServiceNameRegistered: String? = null

    private val _discoveredHosts = MutableStateFlow<List<NsdServiceInfo>>(emptyList())
    val discoveredHosts: StateFlow<List<NsdServiceInfo>> = _discoveredHosts.asStateFlow()

    // Keep host IP state
    private val _hostIpAddress = MutableStateFlow<String?>(null)
    val hostIpAddress: StateFlow<String?> = _hostIpAddress.asStateFlow()

    // Optional: Track services currently being resolved to prevent multiple attempts
    private val resolvingServices = ConcurrentHashMap<String, Boolean>()

    // --- Serialization ---
    private val gson = Gson()

    // --- Logging ---
    private fun log(message: String, tag: String = "GameViewModel") {
        println("[$tag] $message")
    }
    private fun logError(message: String, error: Throwable? = null) {
        val errorMsg = error?.message?.let { ": $it" } ?: ""
        println("[GameViewModel ERROR] $message$errorMsg")
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
        localPlayerId = 0

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
                )
        _connectedPlayersCount.value = 1
        log("Initial GameState created for host setup.")
    }

    /** HOST: Starts ServerSocket and NSD Registration */
    fun startServerAndDiscovery(port: Int = 0) { // Port 0 lets OS pick free port
        if (isServerRunning || !isHost) {
            log("Server already running or not host. Aborting start.")
            return
        }
        log("Attempting to start server and NSD registration...")
        isServerRunning = true
        viewModelScope.launch(Dispatchers.IO) {
            var serverStarted = false
            var nsdRegistered = false
            try {
                // 1. Start Server Socket
                serverSocket = ServerSocket(port)
                val actualPort = serverSocket!!.localPort
                servicePort = actualPort // *** ASSIGN servicePort here ***
                log("Server socket started successfully on port $actualPort.")
                serverStarted = true

                // 2. Get Host IP for display
                val localIp = getLocalIpAddress()
                withContext(Dispatchers.Main) { _hostIpAddress.value = localIp?.hostAddress }
                log("Host IP for display: ${localIp?.hostAddress ?: "Not Found"}")

                // 3. Register NSD Service (using the obtained 'actualPort')
                if (registerNsdService(actualPort)) { // *** PASS actualPort ***
                    nsdRegistered = true
                } else {
                    throw Exception("NSD Registration Failed")
                }

                log("Server and NSD active. Waiting for ${requiredPlayerCount - 1} players...")

                // 4. Accept Client Connections Loop
                while (clientSockets.size < requiredPlayerCount - 1 &&
                        isServerRunning &&
                        isActive) {
                    val socket = serverSocket?.accept() ?: break
                    val currentClientCount = clientSockets.size
                    val assignedPlayerId = currentClientCount + 1

                    log("Client connected, assigning Player ID $assignedPlayerId")

                    try {
                        val writer = PrintWriter(socket.getOutputStream(), true)
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                        clientSockets[assignedPlayerId] = socket
                        clientWriters[assignedPlayerId] = writer
                        clientReaders[assignedPlayerId] = reader

                        sendMessageToClient(
                                assignedPlayerId,
                                NetworkMessage(MessageType.ASSIGN_ID, assignedPlayerId)
                        )
                        // Send current lobby state
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
                            prepareAndBroadcastInitialState()
                        }
                    } catch (e: Exception) {
                        logError("Error during client setup (Player $assignedPlayerId)", e)
                        runCatching { socket.close() }
                    }
                } // End of accept loop
                log("Stopped accepting connections (lobby full or server stopped).")
            } catch (e: Exception) {
                if (isServerRunning) {
                    logError("Server/NSD start failed or accept loop error", e)
                    withContext(Dispatchers.Main) {
                        _showError.emit("Error starting host: ${e.message}")
                    }
                }
                withContext(Dispatchers.Main) {
                    stopServerAndDiscovery()
                } // Ensure cleanup on any error
            } finally {
                log("Server listener loop finished. isServerRunning=$isServerRunning")
                // No need to set isServerRunning = false here, stopServerAndDiscovery handles it
            }
        }
    }

    /**
     * HOST: Registers the game service using NSD. Returns true on success request, false on
     * immediate failure.
     */
    private fun registerNsdService(portToRegister: Int): Boolean { // *** Renamed parameter ***
        if (registrationListener != null) {
            log("NSD registration already in progress or completed. Unregistering first.")
            try {
                nsdManager?.unregisterService(registrationListener)
            } catch (e: Exception) {}
            registrationListener = null
            nsdServiceNameRegistered = null
        }

        nsdManager = applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager?
        if (nsdManager == null) {
            /* Error handling */
            return false
        }

        registrationListener =
                object : NsdManager.RegistrationListener {
                    override fun onServiceRegistered(nsdServiceInfo: NsdServiceInfo) {
                        nsdServiceNameRegistered = nsdServiceInfo.serviceName
                        log(
                                "NSD Service registered: $nsdServiceNameRegistered on port $portToRegister"
                        ) // *** Use parameter ***
                    }
                    override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        logError(
                                "NSD registration failed for ${serviceInfo.serviceName}: Error $errorCode"
                        )
                        viewModelScope.launch {
                            _showError.emit("Failed to advertise game (Error $errorCode)")
                        }
                        nsdServiceNameRegistered = null
                    }
                    override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                        log("NSD Service unregistered: ${arg0.serviceName}")
                        if (arg0.serviceName == nsdServiceNameRegistered) {
                            nsdServiceNameRegistered = null
                        }
                    }
                    override fun onUnregistrationFailed(
                            serviceInfo: NsdServiceInfo,
                            errorCode: Int
                    ) {
                        logError(
                                "NSD unregistration failed for ${serviceInfo.serviceName}: Error $errorCode"
                        )
                    }
                }

        val baseName = "Mindikot"
        val uniqueName = "${baseName}_${(1000..9999).random()}"
        val serviceInfo =
                NsdServiceInfo().apply {
                    setServiceName(uniqueName)
                    setServiceType(SERVICE_TYPE)
                    setPort(portToRegister) // *** Use parameter ***
                }

        log("Attempting to register NSD service: $uniqueName on port $portToRegister")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                nsdManager?.registerService(
                        serviceInfo,
                        NsdManager.PROTOCOL_DNS_SD,
                        registrationListener
                )
                return true
            } else {
                /* Error handling */
                return false
            }
        } catch (e: Exception) {
            /* Error handling */
            return false
        }
    }

    /** HOST: Stops the ServerSocket and unregisters NSD service. */
    fun stopServerAndDiscovery() {
        if (!isHost) return
        if (!isServerRunning && serverSocket == null && registrationListener == null) {
            log("stopServerAndDiscovery called but server/NSD not active.")
            return
        }
        log("Stopping server and NSD...")
        isServerRunning = false

        // 1. Unregister NSD
        if (nsdManager != null && registrationListener != null) {
            log("Unregistering NSD service: $nsdServiceNameRegistered")
            try {
                nsdManager?.unregisterService(registrationListener)
            } catch (e: Exception) {
                /* Error handling */
            } finally {
                registrationListener = null
            } // Clear listener ref
        }

        // 2. Close Server Socket
        runCatching { serverSocket?.close() }.onSuccess { log("Server socket closed.") }.onFailure {
            logError("Error closing server socket", it)
        }
        serverSocket = null
        servicePort = 0 // Reset port

        // 3. Cancel client jobs & close connections
        val clientIds = clientSockets.keys.toList()
        clientIds.forEach { removeClient(it) } // Use removeClient for thorough cleanup

        // 4. Reset host state
        _connectedPlayersCount.value = 0
        _gameStarted.value = false
        _hostIpAddress.value = null
        _state.value = createInitialEmptyGameState()
        log("Server stopped, NSD unregistered, connections closed.")
    }

    // ... (Rest of HOST functions: listenToClient, handleClientMessage, prepareAndBroadcast, etc.)
    // ...
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
                    logError("Deck empty")
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
                    logError("Player index OOB during dealing")
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
            _gameStarted.value = true
        }
    }

    /** HOST: Broadcasts the GameState to all connected clients. */
    private fun broadcastGameState(gameState: GameState) {
        if (!isHost) return
        val message = NetworkMessage(MessageType.GAME_STATE_UPDATE, gameState)
        log("Broadcasting GameState to ${clientWriters.size} clients...")
        clientWriters.keys.toList().forEach { id -> sendMessageToClient(id, message) }
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
                synchronized(writer) { writer.println(messageJson) }
                if (writer.checkError()) {
                    throw Exception("PrintWriter error after write.")
                }
            } catch (e: Exception) {
                logError("Error sending message to Player $playerId", e)
                withContext(Dispatchers.Main) { removeClient(playerId) }
            }
        }
    }

    /** HOST: Cleans up resources associated with a disconnected or removed client. */
    private fun removeClient(playerId: Int) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            if (!clientSockets.containsKey(playerId)) return@launch

            log("Removing client Player $playerId...")
            clientJobs[playerId]?.cancel("Client removed")
            clientJobs.remove(playerId)

            runCatching { clientWriters[playerId]?.close() }
            runCatching { clientReaders[playerId]?.close() }
            runCatching { clientSockets[playerId]?.close() }

            clientWriters.remove(playerId)
            clientReaders.remove(playerId)
            clientSockets.remove(playerId)

            _connectedPlayersCount.value = clientSockets.size + 1

            log(
                    "Player $playerId removed. Connected: ${_connectedPlayersCount.value}/${requiredPlayerCount}"
            )

            if (_gameStarted.value) {
                val playerName =
                        _state.value.players.find { it.id == playerId }?.name ?: "Player $playerId"
                _showError.emit("$playerName disconnected. Game interrupted.")
                broadcastGameState(
                        _state.value.copy(
                                players =
                                        _state.value.players.map {
                                            if (it.id == playerId)
                                                    it.copy(
                                                            name = "$playerName [LEFT]",
                                                            hand = mutableListOf()
                                                    )
                                            else it
                                        }
                        )
                )
                // stopServerAndDiscovery() // Consider stopping the game
            } else {
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

    fun startNsdDiscovery() {
        if (isHost) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { /* Permission check */ }

        log("Client: Starting NSD discovery...")
        stopNsdDiscovery() // Ensure clean state
        _discoveredHosts.value = emptyList()
        resolvingServices.clear() // Clear resolving tracker

        nsdManager = applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager?
        if (nsdManager == null) { /* Error handling */ return }

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) { log("NSD discovery started.") }

            override fun onServiceFound(service: NsdServiceInfo) {
                log("NSD service found raw: ${service.serviceName}, type: ${service.serviceType}")
                // Filter for correct type, avoid self-discovery, and check if already resolving
                if (service.serviceType == SERVICE_TYPE &&
                    service.serviceName != nsdServiceNameRegistered &&
                    !resolvingServices.containsKey(service.serviceName) // Check if already resolving
                )
                {
                    log("Attempting to resolve service: ${service.serviceName}")
                    resolvingServices[service.serviceName] = true // Mark as resolving
                    resolveNsdService(service) // Trigger resolution
                } else {
                    log("Ignoring found service: Type mismatch, self-discovery, or already resolving.")
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                log("NSD service lost: ${service.serviceName}")
                // Update UI on Main thread
                viewModelScope.launch(Dispatchers.Main) {
                    _discoveredHosts.update { list -> list.filterNot { it.serviceName == service.serviceName } }
                }
                resolvingServices.remove(service.serviceName) // Remove from resolving tracker
            }

            override fun onDiscoveryStopped(serviceType: String) { log("NSD discovery stopped.") }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                logError("NSD discovery start failed: Error code $errorCode")
                viewModelScope.launch { _showError.emit("Failed to search for games (Error $errorCode)")}
                stopNsdDiscovery()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                logError("NSD discovery stop failed: Error code $errorCode")
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            } else { /* Handle older API error */ }
        } catch (e: Exception) { /* Error handling */ }
    }

    /** CLIENT: Resolves a discovered service to get host and port */
    private fun resolveNsdService(serviceInfo: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN || nsdManager == null) return

        val serviceName = serviceInfo.serviceName // Capture name for logging in callbacks
        log("Resolving NSD service details for: $serviceName")

        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(failedServiceInfo: NsdServiceInfo, errorCode: Int) {
                logError("NSD resolve failed for ${failedServiceInfo.serviceName}: Error code $errorCode")
                resolvingServices.remove(failedServiceInfo.serviceName) // Remove from tracker on failure
            }

            @Suppress("DEPRECATION") // For host property
            override fun onServiceResolved(resolvedServiceInfo: NsdServiceInfo) {
                log("NSD service RESOLVED: ${resolvedServiceInfo.serviceName} at ${resolvedServiceInfo.host}:${resolvedServiceInfo.port}")
                // Update the list on the Main thread
                viewModelScope.launch(Dispatchers.Main) {
                    _discoveredHosts.update { currentList ->
                        val existingIndex = currentList.indexOfFirst { it.serviceName == resolvedServiceInfo.serviceName }
                        if (existingIndex != -1) {
                            // Update existing entry with resolved info
                            currentList.toMutableList().apply { set(existingIndex, resolvedServiceInfo) }
                        } else {
                            // Add new resolved entry
                            currentList + resolvedServiceInfo
                        }
                    }
                }
                resolvingServices.remove(resolvedServiceInfo.serviceName) // Remove from tracker on success
            }
        }

        try {
            @Suppress("DEPRECATION") // For resolveService method
            nsdManager?.resolveService(serviceInfo, listener)
        } catch (e: Exception) {
            logError("Exception calling resolveService for $serviceName", e)
            resolvingServices.remove(serviceName) // Remove from tracker on exception
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
        } catch (e: Exception) { logError("Error stopping NSD discovery", e) }
        finally {
            discoveryListener = null
            _discoveredHosts.value = emptyList() // Clear list when stopping discovery
            resolvingServices.clear() // Clear resolving tracker
        }
    }

    /** CLIENT: Connects to a selected discovered host */
    fun connectToDiscoveredHost(serviceInfo: NsdServiceInfo, playerName: String) {
        @Suppress("DEPRECATION") // Suppress warning for serviceInfo.host
        val hostAddress = serviceInfo.host?.hostAddress
        val port = serviceInfo.port
        if (hostAddress != null && port > 0) {
            log("Client: Attempting connection to selected host: ${serviceInfo.serviceName} ($hostAddress:$port)")
            connectToServer(hostAddress, port, playerName)
        } else {
            logError("Client: Cannot connect, resolved service info is invalid (missing host/port): $serviceInfo")
            viewModelScope.launch { _showError.emit("Failed to get connection details for '${serviceInfo.serviceName}'. Please refresh.") }
        }
    }

    /** CLIENT: Connects to the game host using IP/Port */
    fun connectToServer(hostAddress: String, port: Int = 8888, playerName: String) {
        if (isConnectedToServer || isHost) return
        log("Client: Attempting connection to $hostAddress:$port...")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                disconnectFromServer() // Ensure cleanup before connect
                delay(100)
                clientSocket = Socket(hostAddress, port)
                clientWriter = PrintWriter(clientSocket!!.getOutputStream(), true)
                clientReader = BufferedReader(InputStreamReader(clientSocket!!.getInputStream()))
                isConnectedToServer = true
                log("Client: Connected successfully.")
                listenToServer()
                sendMessageToServer(NetworkMessage(MessageType.PLAYER_NAME, playerName))
            } catch (e: Exception) {
                logError("Client: Connection to $hostAddress:$port failed", e)
                isConnectedToServer = false
                withContext(Dispatchers.Main) { _showError.emit("Connection failed: ${e.message}") }
                disconnectFromServer() // Cleanup
            }
        }
    }

    /** CLIENT: Listens for messages from the server */
    private fun listenToServer() {
        clientReaderJob?.cancel()
        clientReaderJob =
                viewModelScope.launch(Dispatchers.IO) {
                    log("Client: Listener started.")
                    try {
                        while (isActive) {
                            val messageJson = clientReader?.readLine() ?: break
                            try {
                                val message = gson.fromJson(messageJson, NetworkMessage::class.java)
                                withContext(Dispatchers.Main.immediate) {
                                    handleServerMessage(message)
                                }
                            } catch (e: Exception) {
                                logError("Client: Error handling message", e)
                            }
                        }
                    } catch (e: Exception) {
                        if (isActive) {
                            logError("Client: Socket read error", e)
                        }
                    } finally {
                        log("Client: Listener stopped.")
                        withContext(Dispatchers.Main) { disconnectFromServer() }
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
                    if (updatedState.players.isEmpty() && _state.value.players.isNotEmpty())
                            return // Ignore potential invalid empty state

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
            } catch (e: Exception) {
                logError("Client: Error sending message", e)
                withContext(Dispatchers.Main) { _showError.emit("Connection error.") }
            }
        }
    }

    /** CLIENT: Disconnects from the server and cleans up resources */
    fun disconnectFromServer() {
        if (isHost || (!isConnectedToServer && clientSocket == null)) return

        log("Client: Disconnecting...")
        isConnectedToServer = false
        clientReaderJob?.cancel("Client disconnecting")
        clientReaderJob = null

        runCatching { clientWriter?.close() }
        runCatching { clientReader?.close() }
        runCatching { clientSocket?.close() }

        clientSocket = null
        clientWriter = null
        clientReader = null

        _gameStarted.value = false
        _state.value = createInitialEmptyGameState()
        localPlayerId = -1
        _connectedPlayersCount.value = 0
        log("Client: Disconnected.")
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
                /* Error Handling */
                return@launch
            }

            log("Host: Processing input from Player $actingPlayerId: $playerInput")
            try {
                GameEngine.processPlayerInput(
                        currentState,
                        playerInput
                ) // Modifies currentState directly
                _state.value = currentState // Update StateFlow

                val roundEnded =
                        currentState.players.firstOrNull()?.hand?.isEmpty() == true &&
                                currentState.currentTrickPlays.isEmpty()

                if (roundEnded) {
                    log("Host: Round Ended. Evaluating...")
                    val result = RoundEvaluator.evaluateRound(currentState)
                    log(
                            "Host: Round Result: Winner=${result.winningTeam?.id ?: "Draw"}, Kot=${result.isKot}"
                    )
                    // TODO: Update scores
                    broadcastGameState(currentState) // Broadcast final state
                    delay(200)
                    _navigateToResultScreen.emit(result) // Notify host UI
                    // TODO: Next round logic
                } else {
                    broadcastGameState(currentState) // Broadcast updated state
                }
            } catch (e: IllegalStateException) {
                logError("Host: Invalid move detected for Player $actingPlayerId: ${e.message}")
                sendMessageToClient(
                        actingPlayerId,
                        NetworkMessage(MessageType.ERROR, "Invalid Move: ${e.message}")
                )
                // Re-request input without changing state drastically
                _state.value = GameEngine.requestInput(currentState, actingPlayerId)
                broadcastGameState(_state.value)
            } catch (e: Exception) {
                /* Error handling */
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
            viewModelScope.launch { _showError.emit("Not your turn!") }
            return
        }
        if (expectedInput != InputType.PLAY_CARD && expectedInput != InputType.CHOOSE_TRUMP_SUIT) {
            viewModelScope.launch { _showError.emit("Cannot play card now.") }
            return
        }
        if (currentState.players.find { it.id == localPlayerId }?.hand?.contains(card) != true) {
            viewModelScope.launch { _showError.emit("Card not in hand!") }
            return
        }

        // Client-side valid move check (optional but improves UX)
        val validMoves =
                GameEngine.determineValidMoves(
                        currentState.players.find { it.id == localPlayerId }?.hand ?: emptyList(),
                        currentState.currentTrickPlays,
                        currentState.trumpSuit,
                        currentState.trumpRevealed
                )
        if (!validMoves.contains(card)) {
            logError("UI Action: Invalid card $card played. Valid: $validMoves")
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
            if (currentName != name && name.isNotBlank()) { // Add check for blank name
                nameChanged = true
                val updatedPlayers =
                        currentState.players.map {
                            if (it.id == playerId) it.copy(name = name) else it
                        }
                currentState.copy(players = updatedPlayers)
            } else {
                currentState
            }
        }
        if (nameChanged) {
            broadcastGameState(_state.value)
        }
    }

    /** Gets the local IP address (needs refinement for robustness) */
    private fun getLocalIpAddress(): InetAddress? {
        // This is a basic implementation. Consider libraries or more checks for complex networks.
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()?.toList()
            interfaces
                    ?.flatMap { intf ->
                        intf.inetAddresses?.toList()?.filter { addr ->
                            !addr.isLoopbackAddress &&
                                    addr is java.net.Inet4Address &&
                                    addr.hostAddress?.startsWith("192.168.") ==
                                            true // Common for WiFi, adjust if needed
                        }
                                ?: emptyList()
                    }
                    ?.firstOrNull()
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
        // Optionally nullify NSD manager if strictly needed: nsdManager = null
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
    ASSIGN_ID, // Server -> Client: {data: Int}
    GAME_STATE_UPDATE, // Server -> Client: {data: GameState}
    PLAYER_ACTION, // Client -> Server: {data: Card or GameEngine.Decision}
    PLAYER_NAME, // Client -> Server: {data: String}
    ERROR // Server -> Client: {data: String}
    // Consider: ROUND_RESULT, GAME_OVER, DISCONNECT_NOTICE
}

/** Represents a message sent between the host and clients. */
data class NetworkMessage(
        val type: MessageType,
        val data: Any? = null // Requires careful serialization/deserialization
)
