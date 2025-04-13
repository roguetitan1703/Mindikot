package com.example.mindikot.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
//import border stroke
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke // Import this
import androidx.compose.ui.text.style.TextAlign // Ensure this is imported
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.mindikot.core.engine.GameEngine // Needed for Decision and determineValidMoves
import com.example.mindikot.core.model.Card // Keep Card model import
import com.example.mindikot.core.state.InputType // Keep InputType import
// Corrected ViewModel and Factory imports
import com.example.mindikot.ui.viewmodel.GameViewModel
import com.example.mindikot.ui.viewmodel.factory.GameViewModelFactory
// Import UI components used
import com.example.mindikot.ui.components.OtherPlayerDisplay
import com.example.mindikot.ui.components.CardView
import com.example.mindikot.ui.components.getSuitSymbol
import com.example.mindikot.ui.components.getSuitColor
import kotlinx.coroutines.flow.collectLatest



@Composable
fun GameScreen(
    navController: NavController,
    viewModel: GameViewModel = viewModel(
        factory = GameViewModelFactory(LocalContext.current.applicationContext)
    )
) {
    val gameState by viewModel.state.collectAsState()
    val localPlayerId = viewModel.localPlayerId // Access directly
    val snackbarHostState = remember { SnackbarHostState() }

    // Find the local player object (handle null briefly during init)
    val localPlayer = remember(gameState.players, localPlayerId) {
        gameState.players.find { it.id == localPlayerId }
    }

    // Determine if it's the local player's turn
    val isMyTurn = remember(gameState.awaitingInputFromPlayerIndex, localPlayerId) {
        gameState.awaitingInputFromPlayerIndex == localPlayerId
    }

    // Calculate valid moves when it's our turn and player data is available
    val validMoves: Set<Card> = remember(gameState, localPlayerId, isMyTurn, localPlayer?.hand) {
        if (isMyTurn && localPlayer != null) {
            GameEngine.determineValidMoves(
                playerHand = localPlayer.hand,
                currentTrickPlays = gameState.currentTrickPlays,
                trumpSuit = gameState.trumpSuit,
                trumpRevealed = gameState.trumpRevealed
            ).toSet() // Use Set for faster lookups (O(1) contains check)
        } else {
            emptySet() // Not our turn or player data not ready, no valid moves to calculate/show
        }
    }

    // Listen for errors and show snackbar
    LaunchedEffect(viewModel.showError, snackbarHostState) {
        viewModel.showError.collectLatest { message ->
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short
            )
        }
    }

    // Listen for navigation to results screen event
    LaunchedEffect(viewModel.navigateToResultScreen, navController) {
        viewModel.navigateToResultScreen.collectLatest { result ->
            // Optional: Pass result data to ResultScreen if needed via navigation arguments
            println("[UI - GameScreen] Navigating to Result screen.")
            // Ensure navigation happens only once per event
            if (navController.currentDestination?.route != "result") {
                navController.navigate("result") {
                    // Clear back stack up to lobby
                    popUpTo("lobby") { inclusive = false }
                }
            }
        }
    }

    // Handle back press - maybe ask for confirmation or disconnect?
    // BackHandler { ... }

    Scaffold(snackbarHost = { SnackbarHost(hostState = snackbarHostState) }) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues) // Apply padding from Scaffold
                .padding(8.dp) // Add overall padding inside Scaffold
        ) {

            // --- Top Area: Game Status Header ---
            GameStatusHeader(gameState = gameState)

            Spacer(modifier = Modifier.height(8.dp))

            // --- Middle Area: Board (Other Players + Trick) ---
            PlayerBoard(
                modifier = Modifier.weight(1f), // Takes up available vertical space
                gameState = gameState,
                localPlayerId = localPlayerId
            )

            Spacer(modifier = Modifier.height(8.dp))

            // --- Center Bottom: Action Prompt / Info ---
            ActionPrompt(
                gameState = gameState,
                localPlayerId = localPlayerId
            )

            Spacer(modifier = Modifier.height(8.dp))

            // --- Bottom Area: Local Player Hand & Actions ---
            if (localPlayer != null) {
                LocalPlayerArea(
                    localPlayerHand = localPlayer.hand,
                    isMyTurn = isMyTurn,
                    validMoves = validMoves, // Pass the calculated valid moves
                    requiredInputType = gameState.requiredInputType,
                    onCardSelected = { card -> viewModel.onCardPlayed(card) }, // Call VM method
                    onReveal = { viewModel.onRevealOrPass(GameEngine.Decision.REVEAL) }, // Call VM method
                    onPass = { viewModel.onRevealOrPass(GameEngine.Decision.PASS) } // Call VM method
                )
            } else {
                // Placeholder if local player data isn't ready yet (e.g., client just connected)
                Box(modifier = Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                     CircularProgressIndicator()
                     Text(" Loading your hand...", modifier = Modifier.padding(top = 60.dp))
                }
            }
        }
    }
}

// --- Helper Composables for GameScreen ---

@Composable
fun GameStatusHeader(gameState: com.example.mindikot.core.state.GameState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Display Trump Suit
        val trumpText = gameState.trumpSuit?.let { getSuitSymbol(it) } ?: "None"
        val trumpColor = if (gameState.trumpRevealed) MaterialTheme.colorScheme.primary else Color.Gray
        Text(
            text = "Trump: \$trumpText",
            fontWeight = FontWeight.Bold,
            color = trumpColor
        )

        // Display Tricks Won per Team
        Text(text = "Tricks Won:", fontWeight = FontWeight.Bold)
        Row {
            gameState.teams.forEach { team ->
                val teamScore = gameState.tricksWon[team.id] ?: 0
                 // Optionally color code team scores based on teamId
                 // val teamColor = if(team.id == 1) Color.Blue else Color.Red
                Text(
                    text = " T\${team.id}: \$teamScore",
                    modifier = Modifier.padding(start = 4.dp)
                    // color = teamColor
                )
            }
        }
    }
    Divider() // Add a visual separator below the status header
}

@Composable
fun PlayerBoard(
    modifier: Modifier = Modifier,
    gameState: com.example.mindikot.core.state.GameState,
    localPlayerId: Int
) {
    // Using BoxWithConstraints allows placing elements relative to the container size, good for different screen sizes
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val widthDp = with(density) { constraints.maxWidth.toDp() }
        val heightDp = with(density) { constraints.maxHeight.toDp() }

        // --- Trick Area (Center) ---
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .sizeIn( // Allow flexibility but constrain size
                    maxWidth = widthDp * 0.7f,
                    maxHeight = heightDp * 0.5f
                )
                .padding(vertical = 20.dp), // Add vertical padding to avoid overlap with players
            contentAlignment = Alignment.Center
        ) {
            // Display cards played in the current trick
            LazyRow(
                 horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
                 verticalAlignment = Alignment.CenterVertically
             ) {
                items(gameState.currentTrickPlays, key = { "trick_\${it.first.id}" }) { (player, card) ->
                    // Use a smaller card view for the trick
                    Card(
                        modifier = Modifier
                           .size(width = 50.dp, height = 75.dp) // Fixed size for trick cards
                           .padding(1.dp),
                        elevation = CardDefaults.cardElevation(1.dp),
                        border = BorderStroke(0.5.dp, Color.Gray)
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "\${card.rank.displayName}\${getSuitSymbol(card.suit)}",
                                color = getSuitColor(card.suit), // Use helper from components
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
            if (gameState.currentTrickPlays.isEmpty()) {
                Text("Play Area", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            }
        }

        // --- Other Players ---
        // Filter out the local player and any potentially invalid entries
        val otherPlayers = gameState.players.filter { it.id != localPlayerId && it.id >= 0 }

        // Dynamic positioning based on player count (Simplified example for 4 players)
        // TODO: Enhance layout for 6 players
        val numOtherPlayers = otherPlayers.size
        val alignmentMap4 = mapOf(
            0 to Alignment.CenterStart, // Left
            1 to Alignment.TopCenter,   // Top
            2 to Alignment.CenterEnd    // Right
        )
        val alignmentMap6 = mapOf( // Example for 6 players
             0 to Alignment.CenterStart,
             1 to Alignment.TopStart,
             2 to Alignment.TopCenter,
             3 to Alignment.TopEnd,
             4 to Alignment.CenterEnd
        )
        // Choose map based on total players (assume 4 or 6 for now)
        val alignmentMap = if(gameState.players.size == 6) alignmentMap6 else alignmentMap4

        // Find relative position index for each player (simple cyclic assignment)
        val localPlayerIndex = gameState.players.indexOfFirst { it.id == localPlayerId }
        if (localPlayerIndex != -1) {
             otherPlayers.forEach { player ->
                 val playerIndex = gameState.players.indexOfFirst { it.id == player.id }
                 // Calculate relative index based on local player's position
                 val relativeIndex = (playerIndex - localPlayerIndex -1 + gameState.players.size) % gameState.players.size
                 val alignment = alignmentMap[relativeIndex] ?: Alignment.TopCenter // Default if map doesn't cover

                 OtherPlayerDisplay(
                     player = player,
                     isCurrentTurn = gameState.awaitingInputFromPlayerIndex == player.id,
                     modifier = Modifier.align(alignment) // Align based on calculated position
                 )
             }
        } else {
             // Fallback if local player not found yet - just list them at top?
              Row(Modifier.align(Alignment.TopCenter)) {
                   otherPlayers.forEach { player ->
                        OtherPlayerDisplay(
                           player = player,
                           isCurrentTurn = gameState.awaitingInputFromPlayerIndex == player.id,
                           modifier = Modifier.padding(horizontal = 4.dp)
                       )
                   }
              }
        }
    }
}

@Composable
fun ActionPrompt(gameState: com.example.mindikot.core.state.GameState, localPlayerId: Int) {
    val currentTurnPlayerId = gameState.awaitingInputFromPlayerIndex
    val isMyTurn = currentTurnPlayerId == localPlayerId

    val promptText = when {
        isMyTurn -> { // It's our turn
            when (gameState.requiredInputType) {
                InputType.PLAY_CARD -> "Your Turn: Play a card"
                InputType.CHOOSE_TRUMP_SUIT -> "Your Turn: Play card to set Trump"
                InputType.REVEAL_OR_PASS -> "Your Turn: Reveal Trump or Pass?"
                null -> "Your Turn..." // Should ideally not happen if state logic is correct
            }
        }
        currentTurnPlayerId != null -> { // Someone else's turn
            val waitingPlayerName = gameState.players.find { it.id == currentTurnPlayerId }?.name ?: "Opponent"
            // Add "(Partner)" if they are on the same team?
            "Waiting for \$waitingPlayerName..."
        }
        else -> { // No one's turn (e.g., between tricks, before game start, round end)
            if (gameState.players.isNotEmpty() && gameState.players.all { it.hand.isEmpty()} && gameState.currentTrickPlays.isEmpty()) {
                 "Round Over" // Indicate round end state
            } else if (gameState.currentTrickPlays.isNotEmpty() && gameState.currentTrickPlays.size == gameState.players.size) {
                "Trick Finished..." // Briefly show between tricks
            } else {
                 "" // Default empty if no specific state applies
            }
        }
    }

    Text(
        text = promptText,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .heightIn(min = 24.dp), // Ensure minimum height for the prompt area
        textAlign = TextAlign.Center, // Use TextAlign enum
        style = MaterialTheme.typography.titleMedium, // Slightly larger prompt text
        fontWeight = if (isMyTurn) FontWeight.Bold else FontWeight.Normal,
        color = if (isMyTurn) MaterialTheme.colorScheme.primary else LocalContentColor.current
    )
}

@Composable
fun LocalPlayerArea(
    localPlayerHand: List<Card>,
    isMyTurn: Boolean,
    validMoves: Set<Card>, // Use the Set passed down
    requiredInputType: InputType?,
    onCardSelected: (Card) -> Unit,
    onReveal: () -> Unit,
    onPass: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        // --- Player Hand ---
        // Sort hand for consistent display (e.g., by suit then rank)
         val sortedHand = remember(localPlayerHand) {
             localPlayerHand.sortedWith(compareBy({ it.suit }, { it.rank.value }))
         }

        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy( (-10).dp, Alignment.CenterHorizontally), // Overlap cards slightly
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(sortedHand, key = { "\${it.suit}-\${it.rank}" }) { card ->
                // Determine if the card is playable based on turn and required input
                 val isPlayableNow = isMyTurn &&
                         (requiredInputType == InputType.PLAY_CARD || requiredInputType == InputType.CHOOSE_TRUMP_SUIT)
                 val isValid = card in validMoves // Check against the valid moves set

                CardView( // Use the dedicated CardView component
                    card = card,
                    isValidMove = isValid, // Highlight if it's a valid move
                    isPlayable = isPlayableNow, // Enable/disable click based on turn and input type
                    onCardSelected = onCardSelected,
                    modifier = Modifier.padding(horizontal = 2.dp) // Add slight horizontal padding if needed
                )
            }
        }
        // Show hand size
        Text("Cards: \${localPlayerHand.size}", style = MaterialTheme.typography.bodySmall)


        Spacer(modifier = Modifier.height(8.dp))

        // --- Action Buttons (Conditional) ---
        // Show Reveal/Pass buttons only when it's the player's turn AND that specific input is required
        if (isMyTurn && requiredInputType == InputType.REVEAL_OR_PASS) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp) // Ensure centered
            ) {
                Button(onClick = onReveal) { Text("Reveal Trump") }
                Button(onClick = onPass) { Text("Pass") }
            }
        } else {
             // Reserve space for buttons even when not visible to prevent layout jumps
             Spacer(modifier = Modifier.height(48.dp)) // Approx height of buttons row
        }
    }
}