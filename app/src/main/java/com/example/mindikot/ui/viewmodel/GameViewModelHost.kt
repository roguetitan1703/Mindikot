package com.example.mindikot.ui.viewmodel

import androidx.lifecycle.viewModelScope // Needed for viewModelScope.launch
import com.example.mindikot.core.engine.DeckGenerator
import com.example.mindikot.core.engine.GameEngine
import com.example.mindikot.core.model.GameMode
import com.example.mindikot.core.state.InputType
import com.example.mindikot.ui.viewmodel.network.NetworkMessage
import com.example.mindikot.ui.viewmodel.network.MessageType
import com.example.mindikot.ui.viewmodel.utils.getLocalIpAddress // Import utility
import com.example.mindikot.ui.viewmodel.utils.log
import com.example.mindikot.ui.viewmodel.utils.logError
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.SocketException

// ========================================================================// HOST FUNCTIONS (Implemented as Extension Functions on GameViewModel)
// ========================================================================
/** HOST: Starts ServerSocket and initiates NSD Registration */
fun GameViewModel.startServerAndDiscovery(port: Int = 0) { // Port 0 lets OS pick free port
    if (isServerRunning || !isHost) {
        log("Server already running or not host. Aborting start.")
        return
    }
    log("Attempting to start server and NSD registration...")
    isServerRunning = true // Set flag immediately

    viewModelScope.launch(Dispatchers.IO) {
        var serverStarted = false
        var nsdRegistered = false // Flag to track if registration was successful
        try {
            // 1. Start Server Socket
            serverSocket = ServerSocket(port)
            val actualPort = serverSocket!!.localPort
            // servicePort = actualPort // Access internal property directly
            // Use reflection or make servicePort internal/public in GameViewModel if needed outside Host extensions
            // For now, assume GameViewModel manages its own servicePort property access if needed.
            // Let's update GameViewModel to have internal servicePort setter
            setServicePortInternal(actualPort) // Use internal setter

            log("Server socket started successfully on port $actualPort.")
            serverStarted = true

            // 2. Get Host IP for display (use utility extension)
            val localIp = getLocalIpAddress()
            withContext(Dispatchers.Main) { _hostIpAddress.value = localIp?.hostAddress }
            val hostAddress = localIp?.hostAddress ?: "Not Found"
            log("Host IP for display: ${hostAddress}")

            // 3. Register NSD Service (call NSD extension function)
            if (registerNsdService(actualPort)) { // Call NSD extension
                nsdRegistered = true // Assume registration initiated
                log("NSD registration initiated.")
            } else {
                throw Exception("NSD Registration Failed to initiate") // Failed early
            }

            log("Server and NSD active. Waiting for ${requiredPlayerCount - 1} players...")

            // 4. Accept Client Connections Loop
            while (clientSockets.size < requiredPlayerCount - 1 && isServerRunning && isActive) {
                val socket = serverSocket?.accept() ?: break // Exit if serverSocket is null or closed
                if (!isServerRunning) { // Double-check after accept
                    runCatching { socket.close() }
                    break
                }

                val currentClientCount = clientSockets.size
                if (currentClientCount >= requiredPlayerCount - 1) {
                     log("Lobby is full. Rejecting connection from ${socket.remoteSocketAddress}")
                     // Send Lobby Full message before closing
                     try {
                         val tempWriter = PrintWriter(socket.getOutputStream(), true)
                         val fullMessage = gson.toJson(NetworkMessage(MessageType.LOBBY_FULL, "Game lobby is full."))
                         tempWriter.println(fullMessage)
                         tempWriter.flush() // Ensure message is sent
                         delay(100) // Small delay before closing
                         tempWriter.close()
                     } catch (e: Exception) {
                        logError("Error sending LOBBY_FULL message", e)
                     } finally {
                        runCatching { socket.close() }
                     }
                     continue // Skip adding this client
                }


                val assignedPlayerId = findNextAvailablePlayerId() // Assign next available ID

                log("Client connected from ${socket.remoteSocketAddress}, assigning Player ID $assignedPlayerId")

                try {
                    val writer = PrintWriter(socket.getOutputStream(), true)
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                    // Store client details BEFORE sending messages
                    clientSockets[assignedPlayerId] = socket
                    clientWriters[assignedPlayerId] = writer
                    clientReaders[assignedPlayerId] = reader

                    // Send Assign ID message first
                    sendMessageToClient(assignedPlayerId, NetworkMessage(MessageType.ASSIGN_ID, assignedPlayerId))
                    // Wait briefly? May not be necessary.
                    // delay(50)

                    // Send current lobby state (Game State Update) - Make sure player name is updated first if available
                     // Player name usually sent by client *after* getting ID, so update state later
                    sendMessageToClient(assignedPlayerId, NetworkMessage(MessageType.GAME_STATE_UPDATE, _state.value))

                    // Start listening coroutine for this client
                    listenToClient(assignedPlayerId, reader)

                    // Update connected count on the main thread
                    withContext(Dispatchers.Main.immediate) {
                        _connectedPlayersCount.value = clientSockets.size + 1 // Host + clients
                        log("Player ID $assignedPlayerId added. Connected: ${_connectedPlayersCount.value}/${requiredPlayerCount}")

                        // **** REMOVE THIS BLOCK ****
                        // Check if lobby is now full after adding this player
                        // if (_connectedPlayersCount.value == requiredPlayerCount) {
                        //     log("All players connected! Preparing initial game state...")
                        //     // DO NOT CALL THIS HERE - Wait for Player Name message
                        //     // prepareAndBroadcastInitialState()
                        // }
                        // ***************************

                        // Instead, just broadcast the updated lobby state so the new
                        // client sees the current list (including themselves as Waiting...)
                        // Ensure player name update happens *before* this if possible, but
                        // usually name comes *after* client gets ID. So broadcast current state.
                        broadcastGameState(_state.value) // Broadcast updated lobby list
                    }
                } catch (e: Exception) {
                    logError("Error during client setup (Player $assignedPlayerId)", e)
                    // Clean up resources for this specific failed client connection
                    clientSockets.remove(assignedPlayerId)
                    clientWriters.remove(assignedPlayerId)
                    clientReaders.remove(assignedPlayerId)
                    runCatching { socket.close() }
                    // Update count if we failed to add the player
                    withContext(Dispatchers.Main.immediate) {
                         _connectedPlayersCount.value = clientSockets.size + 1
                    }
                }
            } // End of accept loop

            log("Stopped accepting new connections (lobby full or server stopped). isServerRunning=$isServerRunning")

        } catch (e: SocketException) {
             if (isServerRunning) { // Log error only if we expected to be running
                 logError("ServerSocket Exception (maybe address in use or closed unexpectedly?)", e)
                 withContext(Dispatchers.Main) {
                     _showError.emit("Host Error: ${e.message}")
                 }
             }
        } catch (e: Exception) {
            if (isServerRunning) { // Only log error if we intended to be running
                 logError("Server/NSD start failed or accept loop error", e)
                 withContext(Dispatchers.Main) {
                     _showError.emit("Error starting host: ${e.message}")
                 }
            }
        } finally {
            log("Server listener coroutine finishing. Cleaning up...")
            // Ensure server and NSD are stopped if the loop exits unexpectedly
            // Call stopServerAndDiscovery on the Main thread to ensure proper cleanup
            withContext(Dispatchers.Main) {
                if (isServerRunning) { // Only stop if it was meant to be running
                   stopServerAndDiscovery() // Cleanup NSD, server socket, clients
                }
            }
        }
    }
}

// Helper to find the next available player ID (Host is 0)
private fun GameViewModel.findNextAvailablePlayerId(): Int {
    // Player IDs are 0 (host), 1, 2, ... requiredPlayerCount - 1
    val existingIds = clientSockets.keys + 0 // Include host ID 0
    for (id in 1 until requiredPlayerCount) {
        if (id !in existingIds) {
            return id
        }
    }
    // Should not happen if logic is correct, but return an indicator of error
    logError("Could not find an available Player ID slot!")
    return -1 // Indicate error
}


/** HOST: Stops the ServerSocket and unregisters NSD service. */
fun GameViewModel.stopServerAndDiscovery() {
    if (!isHost) return
    // Check if already stopped to prevent redundant calls
    if (!isServerRunning && serverSocket == null && registrationListener == null && clientSockets.isEmpty()) {
        log("stopServerAndDiscovery called but server/NSD likely already stopped.")
        return
    }
    log("Stopping server and NSD...")
    isServerRunning = false // Signal loops and checks to stop

    // 1. Unregister NSD (call NSD extension)
    unregisterNsdService() // Handles null checks internally

    // 2. Close Server Socket (use runCatching for safety)
    viewModelScope.launch(Dispatchers.IO) { // Close socket off main thread
        runCatching {
            serverSocket?.close() // Close the server socket first
        }.onSuccess {
            log("Server socket closed.")
        }.onFailure { e ->
            // Ignore errors if already closed
            if (e !is SocketException || e.message?.contains("Socket closed", ignoreCase = true) == false) {
                 logError("Error closing server socket", e)
            }
        }
        serverSocket = null // Nullify after attempting close
    }
    setServicePortInternal(0) // Reset port

    // 3. Cancel client jobs & close connections
    // Use a copy of keys to avoid ConcurrentModificationException while iterating
    val clientIds = clientSockets.keys.toList()
    log("Closing connections for clients: $clientIds")
    clientIds.forEach { removeClient(it) } // Use removeClient for thorough cleanup

    // 4. Reset host-specific state (on Main thread)
    viewModelScope.launch(Dispatchers.Main) {
        _connectedPlayersCount.value = 0 // Reset count
        _gameStarted.value = false
        _hostIpAddress.value = null
        // Optionally reset GameState if desired, or keep it for review
        // _state.value = createInitialEmptyGameState()
        log("Server stopped, NSD unregistered, connections closed.")
    }
}

/** HOST: Starts a listener coroutine for a specific client. */
private fun GameViewModel.listenToClient(playerId: Int, reader: BufferedReader) {
    clientJobs[playerId]?.cancel() // Cancel any previous job for this ID

    clientJobs[playerId] = viewModelScope.launch(Dispatchers.IO) {
        log("Listener started for Player $playerId.")
        try {
            while (isActive) { // Loop while coroutine is active
                val messageJson = reader.readLine()
                if (messageJson == null) {
                    log("Player $playerId disconnected (readLine returned null).")
                    break // Exit loop if connection is closed
                }

                // log("Received from Player $playerId: $messageJson") // Can be verbose
                try {
                    // Basic validation: Check if it looks like JSON
                    if (!messageJson.startsWith("{") || !messageJson.endsWith("}")) {
                         logError("Invalid JSON format received from Player $playerId: $messageJson")
                         continue // Skip this message
                    }

                    val message = gson.fromJson(messageJson, NetworkMessage::class.java)

                    // Process message on Main thread for state safety
                    withContext(Dispatchers.Main.immediate) { // Use immediate for faster processing if safe
                        handleClientMessage(playerId, message)
                    }
                } catch (e: JsonSyntaxException) {
                    logError("JSON Parse Error from Player $playerId: ${e.message} for JSON: $messageJson")
                    // Optionally send an error back to the client if possible/needed
                } catch (e: Exception) {
                    logError("Error handling message from Player $playerId", e)
                }
            }
        } catch (e: SocketException) {
             // Ignore "Socket closed" exceptions if not active or server stopped
             if (isActive && isServerRunning && e.message?.contains("Socket closed", ignoreCase = true) == false) {
                  logError("SocketException for Player $playerId", e)
             } else {
                  log("Listener for Player $playerId stopped (Socket closed or intentional).")
             }
        } catch (e: Exception) {
            // Avoid logging errors during intentional cancellation or socket closure
            if (isActive && isServerRunning) {
                logError("Error reading from Player $playerId socket", e)
            } else {
                 log("Listener for Player $playerId stopped (likely intentional close or cancellation).")
            }
        } finally {
            log("Listener coroutine finishing for Player $playerId.")
            // Ensure cleanup (removing client) happens on the Main thread safely
            // regardless of how the loop exited.
            withContext(Dispatchers.Main) {
                removeClient(playerId)
            }
        }
    }

    // Optional: Add invokeOnCompletion for finer-grained logging/cleanup if needed
     clientJobs[playerId]?.invokeOnCompletion { throwable ->
         if (throwable != null && throwable !is CancellationException) {
             logError("Listener job for Player $playerId completed with error", throwable)
         } else {
             // log("Listener job for Player $playerId completed normally or cancelled.")
         }
         // Cleanup should be handled in the finally block primarily
     }
}

/** HOST: Handles messages received from a specific client. (Runs on Main Thread) */
private fun GameViewModel.handleClientMessage(playerId: Int, message: NetworkMessage) {
    log("Handling message: ${message.type} from Player $playerId")
    when (message.type) {
        MessageType.PLAYER_ACTION -> {
            if (_state.value.awaitingInputFromPlayerIndex == playerId) {
                val expectedType = _state.value.requiredInputType
                try {
                    // Deserialize based on expected type - Robust handling needed
                    val actionData: Any? = when (expectedType) {
                        InputType.PLAY_CARD, InputType.CHOOSE_TRUMP_SUIT -> {
                            // Attempt to deserialize directly to Card
                            tryDeserializeJson(message.data, com.example.mindikot.core.model.Card::class.java)
                        }
                        InputType.REVEAL_OR_PASS -> {
                            // Attempt to deserialize directly to Decision enum
                             tryDeserializeJson(message.data, GameEngine.Decision::class.java)
                        }
                        null -> {
                            logError("Received PLAYER_ACTION from Player $playerId but no input type was expected.")
                            null // Error case
                        }
                    }

                    if (actionData != null) {
                        // Input successfully deserialized, process it
                        processGameInput(playerId, actionData) // Pass Player ID for context
                    } else {
                        logError("Failed to parse PLAYER_ACTION data for expected type $expectedType from Player $playerId. Data: ${message.data}")
                        sendMessageToClient(playerId, NetworkMessage(MessageType.ERROR, "Invalid action data format."))
                        // Consider re-requesting input or handling the error more gracefully
                    }
                } catch (e: Exception) {
                    logError("Error deserializing/processing PLAYER_ACTION data from Player $playerId", e)
                    sendMessageToClient(playerId, NetworkMessage(MessageType.ERROR, "Error processing your action."))
                }
            } else {
                log("Received action from Player $playerId but it's not their turn (expected ${_state.value.awaitingInputFromPlayerIndex}). Ignoring.")
                // Optionally send "Not your turn" error, but be careful not to spam
                // sendMessageToClient(playerId, NetworkMessage(MessageType.ERROR, "Not your turn"))
            }
        }
        MessageType.PLAYER_NAME -> {
            val name = message.data as? String
            if (!name.isNullOrBlank()) {
                val previousName = _state.value.players.find { it.id == playerId }?.name
                updatePlayerName(playerId, name) // Update name and broadcast state

                // **** ADD THIS CHECK ****
                // Check if this was the last player joining and if we can now start
                val currentState = _state.value // Get the state *after* name update
                val allPlayersNamed = currentState.players.none {
                    it.name == "Waiting..." || it.name == "[Disconnected]" || it.name.contains("[LEFT]")
                }
                if (previousName == "Waiting..." && // Only trigger if they were previously Waiting
                    _connectedPlayersCount.value == requiredPlayerCount &&
                    allPlayersNamed) {
                    log("Last player ($name - ID $playerId) joined and named. Starting game.")
                    prepareAndBroadcastInitialState() // NOW it's safe to start
                }
                // ***********************

            } else {
                logError("Received invalid or blank PLAYER_NAME data from Player $playerId: ${message.data}")
            }
        }
        // Handle other message types if needed (e.g., PING, REQUEST_STATE etc.)
        else -> log("Received unhandled message type: ${message.type} from Player $playerId")
    }
}

/** HOST: Deals cards, sets hidden card (Mode B), updates state, and broadcasts. (Runs on Main Thread) */
fun GameViewModel.prepareAndBroadcastInitialState() {
    // Ensure this runs on the main thread if called from background
    viewModelScope.launch(Dispatchers.Main.immediate) {
        log("Preparing initial game state for ${requiredPlayerCount} players...")
        val currentPlayers = _state.value.players
        // Validate player count and readiness
        if (currentPlayers.size != requiredPlayerCount ||
            currentPlayers.any { it.name == "Waiting..." || it.name == "[Disconnected]" } ||
            _connectedPlayersCount.value != requiredPlayerCount) {
            logError("Cannot prepare initial state: Incorrect number (${connectedPlayersCount.value}/${requiredPlayerCount}) or incomplete players. Players: ${currentPlayers.map { it.name }}")
            // Maybe send error to clients or host UI
            _showError.emit("Cannot start game. Waiting for all players.")
            return@launch
        }

        // Deck generation now relies on requiredPlayerCount from ViewModel
        val deck = DeckGenerator.generateDeck(requiredPlayerCount)
        var hiddenCard: com.example.mindikot.core.model.Card? = null

        // Set hidden card for Mode B
        if (_state.value.gameMode == GameMode.FIRST_CARD_HIDDEN) {
            if (deck.isNotEmpty()) {
                hiddenCard = deck.removeAt(0) // Take the first card after shuffle
                log("Hidden card set (Mode B): ${hiddenCard.suit}")
            } else {
                logError("Cannot set hidden card: Deck is empty!")
                _showError.emit("Error: Deck empty during setup.")
                return@launch
            }
        }

        // Deal cards
        val updatedPlayers = currentPlayers.toMutableList()
        val cardsPerPlayer = deck.size / requiredPlayerCount
        if (deck.size % requiredPlayerCount != 0) {
            logError("Deck size (${deck.size}) not evenly divisible by $requiredPlayerCount players after potential hidden card!")
            // Decide how to handle extra cards if necessary (e.g., discard, error out)
        }

        var currentCardIndex = 0
        for (i in 0 until requiredPlayerCount) {
            // Ensure player index exists before dealing
             val playerIndex = updatedPlayers.indexOfFirst { it.id == i }
             if (playerIndex == -1) {
                 logError("Player ID $i not found in list for dealing!")
                 continue
             }

            val handEndIndex = currentCardIndex + cardsPerPlayer
            // Make sure we don't go past the end of the deck
            val actualEndIndex = minOf(handEndIndex, deck.size)
            if (currentCardIndex >= actualEndIndex) {
                 logError("Not enough cards left in deck to deal to player $i (index $currentCardIndex)")
                 break // Stop dealing if deck runs out unexpectedly
            }
            val handCards = deck.subList(currentCardIndex, actualEndIndex).toMutableList()

            updatedPlayers[playerIndex] = updatedPlayers[playerIndex].copy(hand = handCards)
            currentCardIndex = actualEndIndex // Move to the next chunk of cards
        }
         log("Cards dealt. ${updatedPlayers.sumOf { it.hand.size }} cards assigned. Remaining deck: ${deck.size - currentCardIndex}")


        // Create the initial game state for the round
        var initialState = _state.value.copy(
            players = updatedPlayers,
            hiddenCard = hiddenCard, // Assign the hidden card (null if not Mode B)
            tricksWon = mutableMapOf(1 to 0, 2 to 0), // Reset tricks won
            currentTrickPlays = mutableListOf(), // Clear current trick
            trumpSuit = null, // Reset trump
            trumpRevealed = false, // Reset trump revealed status
            currentLeaderIndex = 0, // Host leads first trick (or decide based on rules)
            awaitingInputFromPlayerIndex = null, // Will be set by requestInput
            requiredInputType = null // Will be set by requestInput
        )

        // Determine the first action required
        initialState = GameEngine.requestInput(initialState, initialState.currentLeaderIndex)

        // Update the central StateFlow
        _state.value = initialState
        _gameStarted.value = true // Mark game as officially started

        // Broadcast the finalized initial state to all clients
        broadcastGameState(initialState)
        log("Initial GameState prepared and broadcast. Game started.")
    }
}


/** HOST: Broadcasts the GameState to all connected clients. */
fun GameViewModel.broadcastGameState(gameState: com.example.mindikot.core.state.GameState) {
    if (!isHost) return
    val message = NetworkMessage(MessageType.GAME_STATE_UPDATE, gameState)
    val clientIds = clientWriters.keys.toList() // Get a stable list of IDs
    // log("Broadcasting GameState to ${clientIds.size} clients (IDs: $clientIds)...") // Verbose

    clientIds.forEach { id ->
        sendMessageToClient(id, message) // Use the dedicated send function
    }
    // log("Broadcast attempt complete.") // Verbose
}


/** HOST: Sends a specific message to a single client. Handles potential errors. */
fun GameViewModel.sendMessageToClient(playerId: Int, message: NetworkMessage) {
    if (!isHost) return
    val writer = clientWriters[playerId]
    if (writer == null) {
        // log("Cannot send message, writer not found for Player $playerId (already removed?).") // Can be noisy
        return
    }

    viewModelScope.launch(Dispatchers.IO) { // Perform network I/O off the main thread
        try {
            val messageJson = gson.toJson(message)
            // Synchronize access to the writer to prevent garbled messages if multiple sends occur rapidly
            // Although each send is in its own coroutine, multiple coroutines might try to write concurrently.
            synchronized(writer) {
                writer.println(messageJson)
                // Check for errors immediately after writing
                if (writer.checkError()) {
                    // checkError() flushes and returns true if an error occurred previously or during flush.
                    throw Exception("PrintWriter error occurred for Player $playerId after sending.")
                }
            }
             // log("Sent to Player $playerId: ${message.type}") // Optional: Log successful send
        } catch (e: Exception) {
            logError("Error sending message to Player $playerId (${message.type})", e)
            // If sending fails, assume the client is disconnected and remove them.
            // Ensure removal happens on the main thread.
            withContext(Dispatchers.Main) {
                removeClient(playerId)
            }
        }
    }
}

/** HOST: Cleans up resources associated with a disconnected or removed client. (Runs on Main Thread) */
fun GameViewModel.removeClient(playerId: Int) {
     // Ensure this runs on the Main thread
     viewModelScope.launch(Dispatchers.Main.immediate) {
        if (!clientSockets.containsKey(playerId)) {
            // log("Attempted to remove Player $playerId, but they were not found (already removed?).")
            return@launch // Already removed or never existed
        }

        log("Removing client Player $playerId...")

        // 1. Cancel the listening job
        clientJobs[playerId]?.cancel("Client removed or disconnected")
        clientJobs.remove(playerId)

        // 2. Close network resources (use runCatching for safety) in IO context
        val writer = clientWriters.remove(playerId)
        val reader = clientReaders.remove(playerId)
        val socket = clientSockets.remove(playerId)

        launch(Dispatchers.IO) {
            runCatching { writer?.close() }.onFailure { /* Log quietly */ }
            runCatching { reader?.close() }.onFailure { /* Log quietly */ }
            runCatching { socket?.close() }.onFailure { /* Log quietly */ }
            // log("Network resources closed attempt for Player $playerId") // Verbose
        }


        // 3. Update connected player count
        _connectedPlayersCount.value = clientSockets.size + 1 // +1 for host
        log("Player $playerId removed. Connected: ${_connectedPlayersCount.value}/${requiredPlayerCount}")

        // 4. Update Game State and potentially notify others
        val playerName = _state.value.players.find { it.id == playerId }?.name ?: "Player $playerId"

        if (_gameStarted.value) {
            // If game was in progress, mark player as left and notify UI/other players
            log("Game in progress. Marking Player $playerId ($playerName) as disconnected.")
            _showError.emit("$playerName disconnected. Game interrupted.") // Show error on host UI

            // Update the player's state in the GameState
            val updatedPlayers = _state.value.players.map {
                if (it.id == playerId) it.copy(name = "$playerName [LEFT]", hand = mutableListOf()) // Mark as left, clear hand
                else it
            }
            // Check if game can continue (e.g., less than required players left)
            val activePlayers = updatedPlayers.count { !it.name.contains("[LEFT]") }
             var finalState = _state.value.copy(
                 players = updatedPlayers,
                 // Reset input if the disconnected player was supposed to play
                 awaitingInputFromPlayerIndex = if (_state.value.awaitingInputFromPlayerIndex == playerId) null else _state.value.awaitingInputFromPlayerIndex,
                 requiredInputType = if (_state.value.awaitingInputFromPlayerIndex == playerId) null else _state.value.requiredInputType
             )

            if (activePlayers < requiredPlayerCount) { // Or specific minimum like 2?
                 logError("Game cannot continue with only $activePlayers active players.")
                 _showError.emit("Game cannot continue. Not enough players.")
                 // TODO: Decide how to end the game gracefully. Maybe navigate to results with an error state?
                 // For now, just update the state and broadcast. Consider stopping the server.
                 // stopServerAndDiscovery() // Option: Stop server
            }
            // Reset input request if disconnected player was the one to play
            if(finalState.awaitingInputFromPlayerIndex == null && activePlayers >= requiredPlayerCount) {
                 // If input was cleared and game should continue, figure out who's next
                 // This might need more complex logic depending on game rules (skip turn? next player?)
                 // For simplicity, maybe just set to null and let UI show stalled state?
                 log("Input cleared due to player disconnect.")
            }


            _state.value = finalState // Update host state

            // Broadcast the updated state showing the player left
            broadcastGameState(finalState)


        } else {
            // If in lobby, just update the player name to indicate disconnected
            log("Player $playerId ($playerName) disconnected from lobby.")
            val updatedLobbyPlayers = _state.value.players.map {
                if (it.id == playerId) it.copy(name = "[Disconnected]", hand = mutableListOf())
                else it
            }
             val updatedLobbyState = _state.value.copy(players = updatedLobbyPlayers)
            _state.value = updatedLobbyState
            // Broadcast the updated lobby state
            broadcastGameState(updatedLobbyState)
        }
     }
}

/** Helper to safely deserialize JSON, returning null on error */
private fun <T> GameViewModel.tryDeserializeJson(data: Any?, targetClass: Class<T>): T? {
    return try {
        // Check if data is already the target type (possible with some Gson setups)
        if (targetClass.isInstance(data)) {
             @Suppress("UNCHECKED_CAST")
             return data as T
        }
        // Otherwise, assume it's likely a Map from initial deserialization, convert back to JSON then to target class
        val json = gson.toJson(data) // Convert Any? (likely Map) to JSON string
        gson.fromJson(json, targetClass)
    } catch (e: Exception) { // Catch broader exceptions during conversion/parsing
        logError("GSON Deserialization failed for ${targetClass.simpleName}", e)
        null
    }
}