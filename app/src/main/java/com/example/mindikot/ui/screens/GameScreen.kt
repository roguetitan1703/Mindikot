package com.example.mindikot.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.mindikot.ui.GameViewModel
import com.example.mindikot.core.engine.GameEngine // Needed for Decision and determineValidMoves
import com.example.mindikot.core.model.Card
import com.example.mindikot.core.state.InputType
import com.example.mindikot.ui.components.OtherPlayerDisplay
import com.example.mindikot.ui.components.PlayerCardView
import com.example.mindikot.ui.components.getSuitSymbol
import kotlinx.coroutines.flow.collectLatest

@Composable
fun GameScreen(navController: NavController, viewModel: GameViewModel = viewModel()) {
    val gameState by viewModel.state.collectAsState()
    val localPlayerId = viewModel.localPlayerId
    val snackbarHostState = remember { SnackbarHostState() }

    // Find the local player object (handle null briefly during init)
    val localPlayer =
            remember(gameState.players, localPlayerId) {
                gameState.players.find { it.id == localPlayerId }
            }

    // Determine if it's the local player's turn
    val isMyTurn =
            remember(gameState.awaitingInputFromPlayerIndex, localPlayerId) {
                gameState.awaitingInputFromPlayerIndex == localPlayerId
            }

    // Calculate valid moves when it's our turn
    val validMoves =
            remember(gameState, localPlayerId, isMyTurn) {
                if (isMyTurn && localPlayer != null) {
                    GameEngine.determineValidMoves(
                                    playerHand = localPlayer.hand,
                                    currentTrickPlays = gameState.currentTrickPlays,
                                    trumpSuit = gameState.trumpSuit,
                                    trumpRevealed = gameState.trumpRevealed
                            )
                            .toSet() // Use Set for faster lookups
                } else {
                    emptySet()
                }
            }

    // Listen for errors
    LaunchedEffect(Unit) {
        viewModel.showError.collectLatest { message ->
            snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Short)
        }
    }

    // Listen for navigation to results
    LaunchedEffect(Unit) {
        viewModel.navigateToResultScreen.collectLatest { result ->
            // Optional: Pass result data to ResultScreen if needed
            navController.navigate("result") {
                // Clear back stack up to lobby or game screen
                popUpTo("lobby") { inclusive = false } // Or popUpTo("game") { inclusive = true }
            }
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(hostState = snackbarHostState) }) { paddingValues ->
        Column(
                modifier =
                        Modifier.fillMaxSize()
                                .padding(paddingValues)
                                .padding(8.dp) // Overall padding
        ) {

            // --- Top Area: Game Status ---
            GameStatusHeader(gameState = gameState)

            Spacer(modifier = Modifier.height(8.dp))

            // --- Middle Area: Board (Other Players + Trick) ---
            PlayerBoard(
                    modifier = Modifier.weight(1f), // Takes up available space
                    gameState = gameState,
                    localPlayerId = localPlayerId
            )

            Spacer(modifier = Modifier.height(8.dp))

            // --- Center Bottom: Action Prompt / Info ---
            ActionPrompt(gameState = gameState, localPlayerId = localPlayerId)

            Spacer(modifier = Modifier.height(8.dp))

            // --- Bottom Area: Local Player Hand & Actions ---
            if (localPlayer != null) {
                LocalPlayerArea(
                        localPlayerHand = localPlayer.hand,
                        isMyTurn = isMyTurn,
                        validMoves = validMoves,
                        requiredInputType = gameState.requiredInputType,
                        onCardSelected = { card -> viewModel.onCardPlayed(card) },
                        onReveal = { viewModel.onRevealOrPass(GameEngine.Decision.REVEAL) },
                        onPass = { viewModel.onRevealOrPass(GameEngine.Decision.PASS) }
                )
            } else {
                // Placeholder if local player data isn't ready yet
                Text("Loading player data...")
            }
        }
    }
}

// --- Helper Composables for GameScreen ---

@Composable
fun GameStatusHeader(gameState: com.example.mindikot.core.state.GameState) {
    Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
                text = "Trump: ${gameState.trumpSuit?.let { getSuitSymbol(it) } ?: "None"}",
                fontWeight = FontWeight.Bold,
                color =
                        if (gameState.trumpRevealed) MaterialTheme.colorScheme.primary
                        else Color.Gray
        )
        Text(text = "Tricks Won:", fontWeight = FontWeight.Bold)
        Row {
            gameState.teams.forEach { team ->
                Text(
                        text = " T${team.id}: ${gameState.tricksWon[team.id] ?: 0}",
                        modifier = Modifier.padding(start = 4.dp)
                        // Optionally color code team scores
                        )
            }
        }
    }
    Divider() // Add a visual separator
}

@Composable
fun PlayerBoard(
        modifier: Modifier = Modifier,
        gameState: com.example.mindikot.core.state.GameState,
        localPlayerId: Int
) {
    // Basic layout - Needs improvement for better positioning based on player count
    // Using BoxWithConstraints allows placing elements relative to the container size
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val widthDp = with(density) { constraints.maxWidth.toDp() }
        val heightDp = with(density) { constraints.maxHeight.toDp() }

        // --- Trick Area (Center) ---
        Box(
                modifier =
                        Modifier.align(Alignment.Center)
                                .size(
                                        width = widthDp * 0.6f,
                                        height = heightDp * 0.5f
                                ), // Adjust size as needed
                // Optional background/border for trick area
                // .background(Color.LightGray.copy(alpha=0.2f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
        ) {
            // Display cards played in the current trick
            Row { // Simple horizontal layout for now
                gameState.currentTrickPlays.forEach { (_, card) ->
                    // Use a smaller card view for the trick
                    Card(modifier = Modifier.size(50.dp, 75.dp).padding(2.dp)) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                    text = "${card.rank.displayName}${getSuitSymbol(card.suit)}",
                                    color =
                                            com.example.mindikot.ui.components.getSuitColor(
                                                    card.suit
                                            ),
                                    fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }

        // --- Other Players ---
        val otherPlayers = gameState.players.filter { it.id != localPlayerId }
        // Very basic positioning based on typical 4 player layout
        // TODO: Adapt this based on gameState.players.size (4 or 6) dynamically
        otherPlayers.getOrNull(1)?.let { player
            -> // Assumes player ID order corresponds roughly to seating
            OtherPlayerDisplay(
                    player = player,
                    isCurrentTurn = gameState.awaitingInputFromPlayerIndex == player.id,
                    modifier = Modifier.align(Alignment.TopCenter)
            )
        }
        otherPlayers.getOrNull(0)?.let { player -> // Player typically to the left
            OtherPlayerDisplay(
                    player = player,
                    isCurrentTurn = gameState.awaitingInputFromPlayerIndex == player.id,
                    modifier = Modifier.align(Alignment.CenterStart)
            )
        }
        otherPlayers.getOrNull(2)?.let { player -> // Player typically to the right
            OtherPlayerDisplay(
                    player = player,
                    isCurrentTurn = gameState.awaitingInputFromPlayerIndex == player.id,
                    modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
        // Add logic for players 4, 5 for 6-player game (e.g., TopLeft, TopRight, BottomLeft,
        // BottomRight)
    }
}

@Composable
fun ActionPrompt(gameState: com.example.mindikot.core.state.GameState, localPlayerId: Int) {
    val currentTurnPlayerId = gameState.awaitingInputFromPlayerIndex
    val promptText =
            when {
                currentTurnPlayerId == localPlayerId -> {
                    when (gameState.requiredInputType) {
                        InputType.PLAY_CARD -> "Your Turn: Play a card"
                        InputType.CHOOSE_TRUMP_SUIT -> "Your Turn: Play a card to choose trump"
                        InputType.REVEAL_OR_PASS -> "Your Turn: Reveal hidden card or Pass?"
                        null -> "Your Turn..." // Should ideally not happen if state logic is
                    // correct
                    }
                }
                currentTurnPlayerId != null -> {
                    val waitingPlayerName =
                            gameState.players.find { it.id == currentTurnPlayerId }?.name
                                    ?: "Opponent"
                    "Waiting for $waitingPlayerName..."
                }
                else -> {
                    // No one's turn (e.g., between tricks or rounds)
                    // Could show last trick winner briefly here using another state mechanism
                    "" // Or "Trick finished" / "Round Starting" etc.
                }
            }

    Text(
            text = promptText,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold
    )
}

@Composable
fun LocalPlayerArea(
        localPlayerHand: List<Card>,
        isMyTurn: Boolean,
        validMoves: Set<Card>,
        requiredInputType: InputType?,
        onCardSelected: (Card) -> Unit,
        onReveal: () -> Unit,
        onPass: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // --- Player Hand ---
        LazyRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            items(localPlayerHand, key = { "${it.suit}-${it.rank}" }) { card ->
                PlayerCardView(
                        card = card,
                        isValidMove = card in validMoves,
                        isPlayable =
                                isMyTurn &&
                                        (requiredInputType == InputType.PLAY_CARD ||
                                                requiredInputType == InputType.CHOOSE_TRUMP_SUIT),
                        onCardSelected = onCardSelected
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // --- Action Buttons (Conditional) ---
        if (isMyTurn && requiredInputType == InputType.REVEAL_OR_PASS) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = onReveal) { Text("Reveal Trump") }
                Button(onClick = onPass) { Text("Pass") }
            }
        }
    }
}
