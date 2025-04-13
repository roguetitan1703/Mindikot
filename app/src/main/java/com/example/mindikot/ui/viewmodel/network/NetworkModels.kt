package com.example.mindikot.ui.viewmodel.network

import com.example.mindikot.core.state.GameState
import com.example.mindikot.core.model.Card
import com.example.mindikot.core.engine.GameEngine

/** Defines the type of message being sent over the network. */
enum class MessageType {
    ASSIGN_ID,           // Server -> Client: {data: Int}
    GAME_STATE_UPDATE,   // Server -> Client: {data: GameState}
    PLAYER_ACTION,       // Client -> Server: {data: Card or GameEngine.Decision}
    PLAYER_NAME,         // Client -> Server: {data: String}
    ERROR,               // Server -> Client: {data: String}
    DISCONNECTED,        // Server -> Client: Player ID disconnected {data: Int} - Informational
    LOBBY_FULL,          // Server -> Client: {data: String} - When connection is rejected
    KICKED               // Server -> Client: {data: String} - When explicitly removed
    // Consider: ROUND_RESULT, GAME_OVER, KEEP_ALIVE, REQUEST_STATE (Client requesting full update)
}

/** Represents a message sent between the host and clients. */
data class NetworkMessage(
    val type: MessageType,
    val data: Any? = null // Requires careful serialization/deserialization
)