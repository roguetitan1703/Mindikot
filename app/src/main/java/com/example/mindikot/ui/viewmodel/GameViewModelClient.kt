package com.example.mindikot.ui.viewmodel

import android.net.nsd.NsdServiceInfo
import androidx.lifecycle.viewModelScope // Needed for viewModelScope.launch
import com.example.mindikot.core.state.GameState
import com.example.mindikot.ui.viewmodel.network.NetworkMessage
import com.example.mindikot.ui.viewmodel.network.MessageType
import com.example.mindikot.ui.viewmodel.utils.log
import com.example.mindikot.ui.viewmodel.utils.logError
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import java.net.SocketException

// ========================================================================// CLIENT FUNCTIONS (Implemented as Extension Functions on GameViewModel)
// ========================================================================
/** CLIENT: Connects to a selected discovered host */
fun GameViewModel.connectToDiscoveredHost(serviceInfo: NsdServiceInfo, playerName: String) {
    @Suppress("DEPRECATION") // Suppress warning for serviceInfo.host (needed for older APIs)
    val hostAddress = serviceInfo.host?.hostAddress
    val port = serviceInfo.port

    if (hostAddress != null && port > 0) {
        log("Client: Attempting connection to selected host: ${serviceInfo.serviceName} ($hostAddress:$port)")
        // Call the core connect function
        connectToServer(hostAddress, port, playerName)
    } else {
        logError("Client: Cannot connect, resolved service info is invalid (missing host/port): $serviceInfo")
        // Use viewModelScope from the receiver (GameViewModel)
        viewModelScope.launch { _showError.emit("Failed to get connection details for '${serviceInfo.serviceName}'. Please refresh.") }
    }
}


/** CLIENT: Connects to the game host using IP/Port */
fun GameViewModel.connectToServer(hostAddress: String, port: Int, playerName: String) {
    if (isConnectedToServer || isHost) {
        log("Client: Already connected or is host. Aborting connect.")
        return
    }
    log("Client: Attempting connection to $hostAddress:$port...")
    // Ensure previous connection is fully cleaned up before starting new one
    disconnectFromServer() // Call disconnect first

    viewModelScope.launch(Dispatchers.IO) {
        try {
             // Small delay after disconnect before attempting new connection
             delay(200)

            clientSocket = Socket(hostAddress, port)
            // Set socket options if needed (e.g., keepAlive, timeout)
            clientSocket?.keepAlive = true
            // clientSocket?.soTimeout = 15000 // 15 second read timeout - careful with this, might cause disconnects

            clientWriter = PrintWriter(clientSocket!!.getOutputStream(), true)
            clientReader = BufferedReader(InputStreamReader(clientSocket!!.getInputStream()))
            isConnectedToServer = true // Set flag only after successful connection and streams setup
            log("Client: Connected successfully to $hostAddress:$port.")

            // Start listener coroutine *after* streams are set up
            listenToServer()

            // Send player name immediately after connecting
            sendMessageToServer(NetworkMessage(MessageType.PLAYER_NAME, playerName))

        } catch (e: Exception) {
            logError("Client: Connection to $hostAddress:$port failed", e)
            isConnectedToServer = false // Ensure flag is false on failure
            withContext(Dispatchers.Main) {
                _showError.emit("Connection failed: ${e.message}")
            }
            // Ensure cleanup happens even if initial connection fails
             withContext(Dispatchers.Main.immediate) {
                disconnectFromServer() // Cleanup resources
             }
        }
    }
}


/** CLIENT: Listens for messages from the server */
private fun GameViewModel.listenToServer() {
    clientReaderJob?.cancel() // Cancel any existing listener job

    clientReaderJob = viewModelScope.launch(Dispatchers.IO) {
        log("Client: Listener started.")
        try {
            while (isActive) { // Loop while the coroutine is active
                val messageJson = clientReader?.readLine()
                if (messageJson == null) {
                    log("Client: Disconnected from server (readLine returned null).")
                    break // Exit loop if server closes connection
                }

                log("Client: Received from Server: $messageJson") // Can be verbose
                 try {
                     // Basic validation
                     if (!messageJson.startsWith("{") || !messageJson.endsWith("}")) {
                         logError("Client: Invalid JSON format received from Server: $messageJson")
                         continue // Skip this message
                     }
                    val message = gson.fromJson(messageJson, NetworkMessage::class.java)
                    // Process on Main thread for UI/State safety
                    withContext(Dispatchers.Main.immediate) {
                        handleServerMessage(message)
                    }
                 } catch (e: JsonSyntaxException) {
                     logError("Client: JSON Parse Error from Server: ${e.message} for JSON: $messageJson")
                 } catch (e: Exception) {
                    logError("Client: Error handling server message", e)
                }
            }
        } catch (e: SocketException) {
            // Handle common socket errors gracefully (e.g., connection reset, closed)
            if (isActive) { // Don't log error if we intentionally disconnected
                 logError("Client: SocketException in listener (likely disconnection): ${e.message}")
            }
        } catch (e: Exception) {
            // Catch other potential exceptions during readLine
            if (isActive) { // Avoid logging errors during cancellation
                logError("Client: Error reading from server socket", e)
            }
        } finally {
            log("Client: Listener stopped.")
            // Ensure disconnect cleanup happens on the Main thread when listener ends
            withContext(Dispatchers.Main) {
                // Check if still connected flag is set, prevents multiple disconnect calls if already handled
                if (isConnectedToServer) {
                    log("Client: Listener ended, triggering disconnect cleanup.")
                    _showError.emit("Disconnected from host.") // Inform user
                    setGameStartedInternal(false) // Use internal setter

                    disconnectFromServer()
                } else {
                    log("Client: Listener ended, but already marked as disconnected.")
                }
            }
        }
    }

    // Optional: Log job completion
     clientReaderJob?.invokeOnCompletion { throwable ->
         if (throwable != null && throwable !is CancellationException) {
             logError("Client: Listener job completed with error", throwable)
         } else {
             log("Client: Listener job completed.")
         }
     }
}

/** CLIENT: Handles messages received from the server (Runs on Main Thread) */
private fun GameViewModel.handleServerMessage(message: NetworkMessage) {
    log("Client: Handling message: ${message.type}") // Can be verbose
    when (message.type) {
        MessageType.ASSIGN_ID -> {
            // Server sends assigned ID. Gson might parse numbers as Double.
            val id = (message.data as? Double)?.toInt() ?: -1 // Safely convert Double to Int
            if (id != -1) {
                if (localPlayerId == -1) { // Assign only if not already assigned
                    setLocalPlayerIdInternal(id) // Use internal setter
                    log("Client: Assigned Player ID: $localPlayerId")
                } else if (localPlayerId != id) {
                    // This shouldn't happen if logic is correct, but handle defensively
                    logError("Client: Received conflicting Player ID assignment! Current: $localPlayerId, Received: $id. Disconnecting.")
                    viewModelScope.launch { _showError.emit("Network error: ID conflict.") }
                    disconnectFromServer()
                }
                // else: Received same ID again, ignore.
            } else {
                logError("Client: Received invalid ASSIGN_ID data: ${message.data}")
                 // Consider disconnecting if ID assignment fails
                 viewModelScope.launch { _showError.emit("Network error: Invalid ID from host.") }
                 disconnectFromServer()
            }
        }
        MessageType.GAME_STATE_UPDATE -> {
            try {
                // Deserialize the GameState embedded in the message data
                val gameStateJson = gson.toJson(message.data) // Convert Any? back to JSON
                val updatedState = gson.fromJson(gameStateJson, GameState::class.java) // Deserialize

                // Basic validation of the received state
                if (updatedState == null) {
                     logError("Client: Failed to deserialize GameState update.")
                     return
                }
                if (updatedState.players.isEmpty() && _state.value.players.isNotEmpty()) {
                    // Allow empty state if we are also in empty state (initial connect before host setup?)
                    // But ignore if we previously had players and now get an empty list mid-game.
                    logError("Client: Received empty player list in GameState update mid-game. Ignoring.")
                    return // Ignore potential invalid empty state received mid-game
                }

                 // Check if our player ID exists in the new state if we expect it
                 if (localPlayerId != -1 && updatedState.players.none { it.id == localPlayerId } && updatedState.players.isNotEmpty()) {
                     logError("Client: Received GameState update, but local player ID $localPlayerId is missing! Players: ${updatedState.players.map{it.id}}")
                     // Don't disconnect immediately, maybe host removed player intentionally? Wait for KICKED msg?
                     // Or handle based on KICKED message below. If no KICKED, this might mean error.
                     // Let's emit an error for now.
                     viewModelScope.launch { _showError.emit("Removed from game lobby?") }
                     // disconnectFromServer() // Might be too aggressive
                     return
                 }


                // Update the main state flow
                _state.value = updatedState

                // Update connected players count based on the received state
                 val validPlayerCount = updatedState.players.count {
                     it.name != "Waiting..." && it.name != "[Disconnected]" && !it.name.contains("[LEFT]")
                 }
                _connectedPlayersCount.value = validPlayerCount
                log("Client: GameState updated. Players: $validPlayerCount/${updatedState.players.size}. Awaiting: ${updatedState.awaitingInputFromPlayerIndex}. MyID: $localPlayerId") // Verbose

                // Determine if the game has started based on receiving a hand
                val myHand = updatedState.players.find { it.id == localPlayerId }?.hand
                if (!_gameStarted.value && myHand?.isNotEmpty() == true) {
                    setGameStartedInternal(true) // Use internal setter

                    log("Client: Game Started (detected non-empty hand).")
                }


            } catch (e: Exception) {
                logError("Client: Error deserializing GAME_STATE_UPDATE", e)
                // Potentially disconnect if state updates become corrupted
            }
        }
         MessageType.DISCONNECTED -> {
             // Informational message that *another* player left
             val disconnectedPlayerId = (message.data as? Double)?.toInt() ?: -1
             if (disconnectedPlayerId != -1 && disconnectedPlayerId != localPlayerId) {
                 val playerName = _state.value.players.find { it.id == disconnectedPlayerId }?.name ?: "Player $disconnectedPlayerId"
                 log("Client: Received notice that $playerName disconnected.")
                 // The GAME_STATE_UPDATE should reflect the change in the player list eventually.
                 viewModelScope.launch { _showError.emit("$playerName has left the game.") }
             }
         }
         MessageType.LOBBY_FULL -> {
             val msg = message.data as? String ?: "Lobby is full."
             log("Client: Received LOBBY_FULL message.")
             viewModelScope.launch { _showError.emit(msg) }
             disconnectFromServer() // Disconnect as we can't join
         }
         MessageType.KICKED -> {
             val reason = message.data as? String ?: "Removed from game by host."
             logError("Client: Received KICKED message. Reason: $reason")
             viewModelScope.launch { _showError.emit(reason) }
             disconnectFromServer() // Disconnect as we were kicked
         }
        MessageType.ERROR -> {
            val errorMsg = message.data as? String ?: "Unknown server error"
            logError("Client: Received error from server: $errorMsg")
            viewModelScope.launch { _showError.emit("Server Error: $errorMsg") }
            // Decide if the error is critical enough to warrant disconnection
            // if (errorMsg.contains("critical error")) { disconnectFromServer() }
        }
        else -> log("Client: Received unhandled message type: ${message.type}")
    }
}


/** CLIENT: Sends a message to the host server */
fun GameViewModel.sendMessageToServer(message: NetworkMessage) {
    // Capture writer locally to prevent race condition with nullification during disconnect
    val writer = clientWriter

    if (!isConnectedToServer || isHost || writer == null) { // Check captured writer
        log("Client: Cannot send message. Not connected, is host, or writer is null.")
        return
    }

    viewModelScope.launch(Dispatchers.IO) { // Network I/O on background thread
        try {
            val messageJson = gson.toJson(message)
            // Synchronize on the writer to prevent potential race conditions if called rapidly
            synchronized(writer) { // Synchronize on the captured writer
                writer.println(messageJson) // Use captured writer
                if (writer.checkError() == true) {
                    throw Exception("PrintWriter error after sending ${message.type}")
                }
            }
            log("Client: Sent message type: ${message.type}") // Optional success log
        } catch (e: Exception) {
            logError("Client: Error sending message (${message.type})", e)
            // If sending fails, assume connection issue and disconnect
            withContext(Dispatchers.Main) {
                if (isConnectedToServer) { // Avoid showing error if already disconnecting
                    _showError.emit("Connection error sending message. Disconnecting.")
                    disconnectFromServer()
                }
            }
        }
    }
}

/** CLIENT: Disconnects from the server and cleans up resources */
fun GameViewModel.disconnectFromServer() {
    // Prevent disconnect if already disconnected or if this device is the host
    if (isHost || (!isConnectedToServer && clientSocket == null)) {
        log("Client: Already disconnected or is host. Aborting disconnect.")
        return
    }

    log("Client: Disconnecting from server...")
    isConnectedToServer = false // Set flag immediately

    // Cancel the listener job first
    clientReaderJob?.cancel("Client disconnecting")
    clientReaderJob = null

    // Close network resources in a background thread
    // Make copies of the variables before launching the IO scope
    val writerToClose = clientWriter
    val readerToClose = clientReader
    val socketToClose = clientSocket
    clientWriter = null // Nullify main references immediately
    clientReader = null
    clientSocket = null

    viewModelScope.launch(Dispatchers.IO) {
        runCatching { writerToClose?.close() }.onFailure { /* Log quietly */ }
        runCatching { readerToClose?.close() }.onFailure { /* Log quietly */ }
        runCatching { socketToClose?.close() }.onFailure { /* Log quietly */ }
        log("Client: Network resources closed attempt.") // Verbose
    }


    // Reset client-specific state on the Main thread
     viewModelScope.launch(Dispatchers.Main.immediate) { // Use immediate if possible
        setGameStartedInternal(false) // Use internal setter
        // Reset GameState to initial empty state upon disconnection
        _state.value = createInitialEmptyGameState()
        setLocalPlayerIdInternal(-1) // Use internal setter
        _connectedPlayersCount.value = 0 // Reset connection count
         stopNsdDiscovery() // Ensure NSD discovery stops if it was running
        _discoveredHosts.value = emptyList() // Clear discovered hosts list
        log("Client: Disconnected and state reset.")
     }
}