// file: app/src/main/java/com/example/mindikot/ui/screens/GameScreen.kt
package com.example.mindikot.ui.screens

import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity // Import LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.mindikot.core.engine.GameEngine
import com.example.mindikot.core.model.Card
import com.example.mindikot.core.state.GameState // Keep explicit import
import com.example.mindikot.core.state.InputType // Keep explicit import
import com.example.mindikot.ui.components.OtherPlayerDisplay // Assuming these are in components
import com.example.mindikot.ui.components.getSuitSymbol      // Assuming these are in components
import com.example.mindikot.ui.components.getSuitColor       // Assuming these are in components
import com.example.mindikot.ui.viewmodel.GameViewModel
import com.example.mindikot.ui.viewmodel.factory.GameViewModelFactory
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun GameScreen(
    navController: NavController,
    viewModel: GameViewModel = viewModel(
        factory = GameViewModelFactory(LocalContext.current.applicationContext)
    )
) {
    val gameState by viewModel.state.collectAsState()
    val localPlayerId = viewModel.localPlayerId // Read once
    val snackbarHostState = remember { SnackbarHostState() }
    val gameStarted by viewModel.gameStarted.collectAsState()
    val isHost = viewModel.isHost // Read once

    // --- Launched Effects ---
    LaunchedEffect(gameState) {
        Log.d("GameScreen", "New game state observed: ${gameState.hashCode()}") // Log hash or specific parts
    }

    LaunchedEffect(gameStarted, isHost, navController) {
        // This logic might need refinement depending on desired navigation flow
        if (!gameStarted) {
            Log.d("GameScreen", "Game not started. isHost: $isHost. Current route: ${navController.currentDestination?.route}")
            // Example: Navigate back to lobby if not host and game stops/resets
            // Or navigate to waiting if host and game stops/resets
            // This depends on when gameStarted becomes false. Avoid navigating if already there.
            // if (!isHost && navController.currentDestination?.route != "lobby") {
            //     navController.navigate("lobby") { popUpTo("game") { inclusive = true } }
            // } else if (isHost && navController.currentDestination?.route != "lobby") { // Host might go back to lobby too
            //      navController.navigate("lobby") { popUpTo("game") { inclusive = true } }
            // }
        }
    }

    val localPlayer = remember(gameState.players, localPlayerId) {
        gameState.players.find { it.id == localPlayerId }
    }

    val isMyTurn = remember(gameState.awaitingInputFromPlayerIndex, localPlayerId) {
        gameState.awaitingInputFromPlayerIndex == localPlayerId
    }

    val validMoves: Set<Card> = remember(gameState, localPlayer?.hand, isMyTurn) { // Depend on hand
        if (isMyTurn && localPlayer != null) {
            GameEngine.determineValidMoves(
                playerHand = localPlayer.hand,
                currentTrickPlays = gameState.currentTrickPlays,
                trumpSuit = gameState.trumpSuit,
                trumpRevealed = gameState.trumpRevealed
            ).toSet()
        } else {
            emptySet()
        }
    }

    LaunchedEffect(viewModel.showError, snackbarHostState) {
        viewModel.showError.collectLatest { message ->
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short
            )
        }
    }

    LaunchedEffect(viewModel.navigateToResultScreen, navController) {
        viewModel.navigateToResultScreen.collectLatest { result -> // Capture result if needed
            Log.d("GameScreen", "Navigating to result screen.")
            if (navController.currentDestination?.route != "result") {
                // Consider passing result data if needed by the result screen
                navController.navigate("result") {
                    // Pop up to the screen *before* the game started (e.g., lobby or setup)
                    popUpTo("lobby") { inclusive = false } // Adjust route as needed
                    launchSingleTop = true // Avoid multiple instances
                }
            }
        }
    }

    // --- UI Structure ---
    Scaffold(snackbarHost = { SnackbarHost(hostState = snackbarHostState) }) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(8.dp)
        ) {
            // Pass relevant parts of gameState if components don't need the whole thing
            GameStatusHeader(
                trumpSuit = gameState.trumpSuit,
                trumpRevealed = gameState.trumpRevealed,
                teams = gameState.teams,
                tricksWon = gameState.tricksWon
            )
            Spacer(modifier = Modifier.height(8.dp))

            PlayerBoard(
                modifier = Modifier.weight(1f),
                players = gameState.players,
                currentTrickPlays = gameState.currentTrickPlays,
                awaitingInputFromPlayerIndex = gameState.awaitingInputFromPlayerIndex,
                localPlayerId = localPlayerId
            )

            Spacer(modifier = Modifier.height(8.dp))

            ActionPrompt(
                players = gameState.players,
                currentTrickPlays = gameState.currentTrickPlays,
                awaitingInputFromPlayerIndex = gameState.awaitingInputFromPlayerIndex,
                requiredInputType = gameState.requiredInputType,
                localPlayerId = localPlayerId
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Use derivedStateOf for potentially complex state transformations
            val localPlayerState = remember { derivedStateOf { gameState.players.find { it.id == localPlayerId } } }

            AnimatedContent(
                targetState = localPlayerState.value, // Use the derived state
                transitionSpec = {
                    fadeIn(animationSpec = tween(300)) with fadeOut(animationSpec = tween(300))
                },
                label = "LocalPlayerAreaTransition"
            ) { player ->
                if (player != null) {
                    LocalPlayerArea(
                        localPlayerHand = player.hand, // Pass immutable list
                        isMyTurn = isMyTurn,
                        validMoves = validMoves,
                        requiredInputType = gameState.requiredInputType, // Pass current input type
                        onCardSelected = { card -> viewModel.onCardPlayed(card) }, // Explicit parameter name
                        onReveal = { viewModel.onRevealOrPass(GameEngine.Decision.REVEAL) },
                        onPass = { viewModel.onRevealOrPass(GameEngine.Decision.PASS) }
                    )
                } else {
                    // Placeholder while loading or if player is not found (shouldn't happen in normal flow)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                        Text(" Loading your hand...", modifier = Modifier.padding(top = 60.dp))
                    }
                }
            }
        }
    }
} // <<< END OF GameScreen COMPOSABLE

// --- Child Composables (GameStatusHeader, PlayerBoard, ActionPrompt, LocalPlayerArea, CardView) ---

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun GameStatusHeader(
    trumpSuit: com.example.mindikot.core.model.Suit?, // Use specific type
    trumpRevealed: Boolean,
    teams: List<com.example.mindikot.core.model.Team>, // Use specific type
    tricksWon: Map<Int, Int> // Use specific type
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val trumpText = trumpSuit?.let { getSuitSymbol(it) } ?: "None"
        val trumpColor by animateColorAsState( // Use 'by' delegate
            targetValue = if (trumpRevealed) MaterialTheme.colorScheme.primary else Color.Gray,
            animationSpec = tween(500), label = "TrumpColorAnimation"
        ) // Remove .value here

        Text(
            text = "Trump: $trumpText",
            fontWeight = FontWeight.Bold,
            color = trumpColor // Use delegate directly
        )

        Row(verticalAlignment = Alignment.CenterVertically) { // Align items vertically
            Text("Tricks:", fontWeight = FontWeight.Bold) // Shortened text
            teams.forEach { team ->
                val teamScore = tricksWon[team.id] ?: 0
                // Animate score changes
                AnimatedContent(
                    targetState = teamScore,
                    transitionSpec = {
                        (slideInVertically { height -> height } + fadeIn()).togetherWith(slideOutVertically { height -> -height } + fadeOut())
                    },
                    label = "ScoreAnimation"
                ) { score ->
                    Text(
                        text = " T${team.id}: $score",
                        modifier = Modifier.padding(start = 6.dp),
                        style = MaterialTheme.typography.bodyMedium // Adjust style if needed
                    )
                }
            }
        }
    }
    Divider()
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun PlayerBoard(
    modifier: Modifier = Modifier,
    players: List<com.example.mindikot.core.model.Player>, // Use specific type
    currentTrickPlays: List<Pair<com.example.mindikot.core.model.Player, Card>>, // Use specific type
    awaitingInputFromPlayerIndex: Int?, // Use specific type
    localPlayerId: Int
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val density = LocalDensity.current // Correct way to get density
        val widthDp = with(density) { constraints.maxWidth.toDp() }
        val heightDp = with(density) { constraints.maxHeight.toDp() }

        // --- Center Play Area ---
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .sizeIn(maxWidth = widthDp * 0.7f, maxHeight = heightDp * 0.5f) // Limit size
                .padding(vertical = 20.dp), // Add padding around play area
            contentAlignment = Alignment.Center
        ) {
            AnimatedContent(
                targetState = currentTrickPlays,
                transitionSpec = { fadeIn(animationSpec = tween(200)) togetherWith fadeOut(animationSpec = tween(200)) },
                label = "TrickPlayTransition"
            ) { plays ->
                if (plays.isEmpty()) {
                    Text("Play Area", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy((-10).dp, Alignment.CenterHorizontally), // Overlap cards slightly
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        items(plays, key = { "trick_${it.first.id}_${it.second.suit}_${it.second.rank}" }) { (player, card) -> // More specific key
                            // Use CardView for consistency? Or a specific TrickCard view
                            Card(
                                modifier = Modifier
                                    .size(width = 50.dp, height = 75.dp)
                                    .padding(horizontal = 1.dp), // Minimal horizontal padding
                                elevation = CardDefaults.cardElevation(1.dp),
                                border = BorderStroke(0.5.dp, Color.Gray)
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "${card.rank.displayName}${getSuitSymbol(card.suit)}",
                                        color = getSuitColor(card.suit),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- Other Player Displays ---
        val otherPlayers = remember(players, localPlayerId) { players.filter { it.id != localPlayerId && it.id >= 0 } }
        val playerCount = players.size
        // Define fixed positions for 4 and 6 players for better stability
        val alignmentMap4 = remember { mapOf(0 to Alignment.CenterStart, 1 to Alignment.TopCenter, 2 to Alignment.CenterEnd) } // Partner across, opponents side/top
        val alignmentMap6 = remember { mapOf(0 to Alignment.CenterStart, 1 to Alignment.TopStart, 2 to Alignment.TopCenter, 3 to Alignment.TopEnd, 4 to Alignment.CenterEnd) } // Around the table
        val alignmentMap = if (playerCount == 6) alignmentMap6 else alignmentMap4

        val localPlayerIndex = remember(players, localPlayerId) { players.indexOfFirst { it.id == localPlayerId } }

        if (localPlayerIndex != -1) {
            otherPlayers.forEach { player ->
                val playerIndex = players.indexOfFirst { it.id == player.id }
                // Calculate position relative to local player (0 = left, 1 = top-left/top, 2 = top-right/right etc.)
                val relativeIndex = (playerIndex - localPlayerIndex -1 + playerCount + playerCount ) % playerCount // Correct relative index calc
                // Ensure relativeIndex is within the map keys
                val indexForMap = if (playerCount == 4) {
                    when (relativeIndex) {
                        0 -> 0 // Left
                        1 -> 1 // Across (Top)
                        2 -> 2 // Right
                        else -> 1 // Default to top if calculation is off
                    }
                } else { // playerCount == 6 (assuming only 4 or 6)
                    relativeIndex // Use direct relative index for 6 players
                }


                val alignment = alignmentMap[indexForMap] ?: Alignment.Center // Default alignment
                key(player.id) { // Add key for stability
                    OtherPlayerDisplay(
                        player = player,
                        isCurrentTurn = awaitingInputFromPlayerIndex == player.id,
                        modifier = Modifier.align(alignment)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun ActionPrompt(
    players: List<com.example.mindikot.core.model.Player>,
    currentTrickPlays: List<Pair<com.example.mindikot.core.model.Player, Card>>,
    awaitingInputFromPlayerIndex: Int?,
    requiredInputType: InputType?,
    localPlayerId: Int
) {
    val currentTurnPlayerId = awaitingInputFromPlayerIndex
    val isMyTurn = currentTurnPlayerId == localPlayerId

    val promptText = when {
        isMyTurn -> when (requiredInputType) {
            InputType.PLAY_CARD -> "Your Turn: Play a card"
            InputType.CHOOSE_TRUMP_SUIT -> "Your Turn: Play card to set Trump"
            InputType.REVEAL_OR_PASS -> "Your Turn: Reveal Trump or Pass?"
            null -> "Your Turn..." // Should ideally not happen if isMyTurn is true
        }

        currentTurnPlayerId != null -> {
            val waitingPlayerName = players.find { it.id == currentTurnPlayerId }?.name ?: "Player $currentTurnPlayerId"
            "Waiting for $waitingPlayerName..."
        }

        else -> {
            // Check for end of round/trick conditions
            val handsEmpty = players.all { it.hand.isEmpty() }
            val trickComplete = currentTrickPlays.isNotEmpty() && currentTrickPlays.size == players.size

            if (handsEmpty && currentTrickPlays.isEmpty()) {
                "Round Over"
            } else if (trickComplete) {
                "Trick Finished..."
            } else {
                // Game start or between tricks
                ""
            }
        }
    }

    AnimatedContent(
        targetState = promptText,
        transitionSpec = { fadeIn(animationSpec = tween(300)).togetherWith(fadeOut(animationSpec = tween(300))) },
        label = "PromptTransition"
    ) { text ->
        Text(
            text = text,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .heightIn(min = 24.dp), // Ensure minimum height
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (isMyTurn) FontWeight.Bold else FontWeight.Normal,
            color = if (isMyTurn) MaterialTheme.colorScheme.primary else LocalContentColor.current
        )
    }
}

@OptIn(ExperimentalAnimationApi::class, ExperimentalFoundationApi::class)
@Composable
fun LocalPlayerArea(
    localPlayerHand: List<Card>, // Expect immutable List
    isMyTurn: Boolean,
    validMoves: Set<Card>,
    requiredInputType: InputType?,
    onCardSelected: (Card) -> Unit,
    onReveal: () -> Unit,
    onPass: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Your Hand (${localPlayerHand.size})", // Show card count
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleSmall
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Sort hand for consistent display (e.g., by suit then rank)
        val sortedHand = remember(localPlayerHand) {
            localPlayerHand.sortedWith(compareBy({ it.suit.ordinal }, { it.rank.value }))
        }

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(
                space = (-15).dp, // Overlap cards for hand feel
                alignment = Alignment.CenterHorizontally
            ),
            contentPadding = PaddingValues(horizontal = 30.dp) // Add padding so ends aren't cut off
        ) {
            items(sortedHand, key = { "${it.suit}-${it.rank}" }) { card ->
                val isValid = validMoves.contains(card)
                // A card is playable if it's my turn AND ( (input is PLAY_CARD and it's a valid move) OR (input is CHOOSE_TRUMP_SUIT) )
                val isPlayable = isMyTurn &&
                        ( (requiredInputType == InputType.PLAY_CARD && isValid) ||
                                (requiredInputType == InputType.CHOOSE_TRUMP_SUIT) ) // Any card playable when choosing trump

                key(card) { // Add key here for better animation tracking
                    CardView(
                        modifier = Modifier.animateItem(
                            fadeInSpec = null, fadeOutSpec = null, placementSpec = spring(
                                stiffness = Spring.StiffnessMediumLow,
                                visibilityThreshold = IntOffset.VisibilityThreshold
                            )
                        ), // Animate card movement
                        card = card,
                        onClick = {
                            if (isPlayable) { // Use the calculated isPlayable flag
                                onCardSelected(card)
                            }
                        },
                        enabled = isPlayable, // Enable based on playability
                        highlight = isPlayable // Highlight if playable (valid and turn)
                    )
                }
                // Remove Spacer, handled by LazyRow arrangement
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Reveal/Pass Buttons
        AnimatedVisibility(
            visible = isMyTurn && requiredInputType == InputType.REVEAL_OR_PASS,
            enter = fadeIn(tween(300)) + slideInVertically(initialOffsetY = { it / 2 }),
            exit = fadeOut(tween(200)) + slideOutVertically(targetOffsetY = { it / 2 })
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onReveal,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Reveal Trump")
                }
                OutlinedButton(onClick = onPass) {
                    Text("Pass")
                }
            }
        }
    }
}

@Composable
fun CardView( // This component remains largely the same
    card: Card,
    onClick: () -> Unit,
    enabled: Boolean = true,
    highlight: Boolean = false,
    modifier: Modifier = Modifier
) {
    val borderColor by animateColorAsState(
        targetValue = if (highlight) MaterialTheme.colorScheme.primary else Color.DarkGray, // Use DarkGray for better contrast
        animationSpec = tween(durationMillis = 300), label = "BorderColorAnim"
    )

    val elevation by animateDpAsState(
        targetValue = if (highlight) 8.dp else 2.dp,
        animationSpec = tween(300), label = "ElevationAnim"
    )

    val alpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.5f, // Make disabled cards more visible
        animationSpec = tween(300), label = "AlphaAnim"
    )

    // Scale animation on highlight
    val scale by animateFloatAsState(
        targetValue = if (highlight) 1.05f else 1.0f,
        animationSpec = tween(300), label = "ScaleAnim"
    )

    Card(
        modifier = modifier
            .size(width = 60.dp, height = 90.dp)
            .graphicsLayer {
                this.alpha = alpha
                this.scaleX = scale
                this.scaleY = scale
            }
            .clickable(
                enabled = enabled,
                onClick = onClick
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        border = BorderStroke(if (highlight) 2.dp else 1.dp, borderColor), // Thicker border when highlighted
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant) // Use surface variant
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Text(
                text = "${card.rank.displayName}${getSuitSymbol(card.suit)}",
                color = getSuitColor(card.suit),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp // Slightly smaller font
            )
        }
    }
}
