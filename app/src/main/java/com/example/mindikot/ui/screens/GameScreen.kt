package com.example.mindikot.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.mindikot.core.engine.GameEngine
import com.example.mindikot.core.model.Card
import com.example.mindikot.core.state.InputType
import com.example.mindikot.ui.components.OtherPlayerDisplay
import com.example.mindikot.ui.components.CardView
import com.example.mindikot.ui.components.getSuitSymbol
import com.example.mindikot.ui.components.getSuitColor
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
    val localPlayerId = viewModel.localPlayerId
    val snackbarHostState = remember { SnackbarHostState() }
    val gameStarted by viewModel.gameStarted.collectAsState()
    val isHost = viewModel.isHost

    LaunchedEffect(gameStarted) {
        if (!gameStarted && !isHost) {
            navController.navigate("lobby")
        } else if (!gameStarted && isHost) {
            navController.navigate("waiting_for_players")
        }
    }

    val localPlayer = remember(gameState.players, localPlayerId) {
        gameState.players.find { it.id == localPlayerId }
    }

    val isMyTurn = remember(gameState.awaitingInputFromPlayerIndex, localPlayerId) {
        gameState.awaitingInputFromPlayerIndex == localPlayerId
    }

    val validMoves: Set<Card> = remember(gameState, localPlayerId, isMyTurn, localPlayer?.hand) {
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
        viewModel.navigateToResultScreen.collectLatest {
            if (navController.currentDestination?.route != "result") {
                navController.navigate("result") {
                    popUpTo("lobby") { inclusive = false }
                }
            }
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(hostState = snackbarHostState) }) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(8.dp)
        ) {
            GameStatusHeader(gameState = gameState)
            Spacer(modifier = Modifier.height(8.dp))

            PlayerBoard(
                modifier = Modifier.weight(1f),
                gameState = gameState,
                localPlayerId = localPlayerId
            )

            Spacer(modifier = Modifier.height(8.dp))

            ActionPrompt(
                gameState = gameState,
                localPlayerId = localPlayerId
            )

            Spacer(modifier = Modifier.height(8.dp))

            AnimatedContent(
                targetState = localPlayer,
                transitionSpec = {
                    fadeIn(animationSpec = tween(300)) with fadeOut(animationSpec = tween(300))
                },
                label = "LocalPlayerAreaTransition"
            ) { player ->
                if (player != null) {
                    LocalPlayerArea(
                        localPlayerHand = player.hand,
                        isMyTurn = isMyTurn,
                        validMoves = validMoves,
                        requiredInputType = gameState.requiredInputType,
                        onCardSelected = { viewModel.onCardPlayed(it) },
                        onReveal = { viewModel.onRevealOrPass(GameEngine.Decision.REVEAL) },
                        onPass = { viewModel.onRevealOrPass(GameEngine.Decision.PASS) }
                    )
                } else {
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
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun GameStatusHeader(gameState: com.example.mindikot.core.state.GameState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val trumpText = gameState.trumpSuit?.let { getSuitSymbol(it) } ?: "None"
        val trumpColor = animateColorAsState(
            targetValue = if (gameState.trumpRevealed) MaterialTheme.colorScheme.primary else Color.Gray,
            animationSpec = tween(500), label = "TrumpColorAnimation"
        ).value

        Text(
            text = "Trump: $trumpText",
            fontWeight = FontWeight.Bold,
            color = trumpColor
        )

        Row {
            Text("Tricks Won:", fontWeight = FontWeight.Bold)
            gameState.teams.forEach { team ->
                val teamScore = gameState.tricksWon[team.id] ?: 0
                AnimatedContent(
                    targetState = teamScore,
                    transitionSpec = {
                        slideInVertically() + fadeIn() with fadeOut()
                    },
                    label = "ScoreAnimation"
                ) { score ->
                    Text(
                        text = " T${team.id}: $score",
                        modifier = Modifier.padding(start = 4.dp)
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
    gameState: com.example.mindikot.core.state.GameState,
    localPlayerId: Int
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val widthDp = with(density) { constraints.maxWidth.toDp() }
        val heightDp = with(density) { constraints.maxHeight.toDp() }

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .sizeIn(maxWidth = widthDp * 0.7f, maxHeight = heightDp * 0.5f)
                .padding(vertical = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            AnimatedContent(
                targetState = gameState.currentTrickPlays,
                transitionSpec = { fadeIn() with fadeOut() },
                label = "TrickPlayTransition"
            ) { plays ->
                if (plays.isEmpty()) {
                    Text("Play Area", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        items(plays, key = { "trick_${it.first.id}" }) { (_, card) ->
                            Card(
                                modifier = Modifier
                                    .size(width = 50.dp, height = 75.dp)
                                    .padding(1.dp),
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

        val otherPlayers = gameState.players.filter { it.id != localPlayerId && it.id >= 0 }
        val alignmentMap4 = mapOf(0 to Alignment.CenterStart, 1 to Alignment.TopCenter, 2 to Alignment.CenterEnd)
        val alignmentMap6 = mapOf(0 to Alignment.CenterStart, 1 to Alignment.TopStart, 2 to Alignment.TopCenter, 3 to Alignment.TopEnd, 4 to Alignment.CenterEnd)
        val alignmentMap = if (gameState.players.size == 6) alignmentMap6 else alignmentMap4

        val localPlayerIndex = gameState.players.indexOfFirst { it.id == localPlayerId }
        if (localPlayerIndex != -1) {
            otherPlayers.forEach { player ->
                val playerIndex = gameState.players.indexOfFirst { it.id == player.id }
                val relativeIndex = (playerIndex - localPlayerIndex - 1 + gameState.players.size) % gameState.players.size
                val alignment = alignmentMap[relativeIndex] ?: Alignment.TopCenter
                OtherPlayerDisplay(
                    player = player,
                    isCurrentTurn = gameState.awaitingInputFromPlayerIndex == player.id,
                    modifier = Modifier.align(alignment)
                )
            }
        }
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun ActionPrompt(gameState: com.example.mindikot.core.state.GameState, localPlayerId: Int) {
    val currentTurnPlayerId = gameState.awaitingInputFromPlayerIndex
    val isMyTurn = currentTurnPlayerId == localPlayerId

    val promptText = when {
        isMyTurn -> when (gameState.requiredInputType) {
            InputType.PLAY_CARD -> "Your Turn: Play a card"
            InputType.CHOOSE_TRUMP_SUIT -> "Your Turn: Play card to set Trump"
            InputType.REVEAL_OR_PASS -> "Your Turn: Reveal Trump or Pass?"
            null -> "Your Turn..."
        }

        currentTurnPlayerId != null -> {
            val waitingPlayerName = gameState.players.find { it.id == currentTurnPlayerId }?.name ?: "Opponent"
            "Waiting for $waitingPlayerName..."
        }

        else -> {
            if (gameState.players.all { it.hand.isEmpty() } && gameState.currentTrickPlays.isEmpty()) {
                "Round Over"
            } else if (gameState.currentTrickPlays.size == gameState.players.size) {
                "Trick Finished..."
            } else {
                ""
            }
        }
    }

    AnimatedContent(
        targetState = promptText,
        transitionSpec = { fadeIn() with fadeOut() },
        label = "PromptTransition"
    ) { text ->
        Text(
            text = text,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .heightIn(min = 24.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (isMyTurn) FontWeight.Bold else FontWeight.Normal,
            color = if (isMyTurn) MaterialTheme.colorScheme.primary else LocalContentColor.current
        )
    }
}

@OptIn(ExperimentalAnimationApi::class)
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Your Hand",
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleSmall
        )

        Spacer(modifier = Modifier.height(8.dp))
// --- Player Hand ---
        // Sort hand for consistent display (e.g., by suit then rank)
         val sortedHand = remember(localPlayerHand) {
             localPlayerHand.sortedWith(compareBy({ it.suit }, { it.rank.value }))
         }

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            contentPadding = PaddingValues(horizontal = 16.dp)
        ) {
            items(sortedHand, key = { "${it.suit}-${it.rank}" }) { card ->
                val isValid = validMoves.contains(card)
                val isDisabled = isMyTurn && requiredInputType == InputType.PLAY_CARD && !isValid

                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn() + scaleIn(initialScale = 0.8f),
                    exit = fadeOut() + scaleOut(targetScale = 0.8f)
                ) {
                    CardView(
                        card = card,
                        onClick = {
                            if (isMyTurn && requiredInputType == InputType.PLAY_CARD && isValid) {
                                onCardSelected(card)
                            }
                        },
                        enabled = !isDisabled,
                        highlight = isMyTurn && isValid
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

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
fun CardView(
    card: Card,
    onClick: () -> Unit,
    enabled: Boolean = true,
    highlight: Boolean = false,
    modifier: Modifier = Modifier
) {
    val borderColor by animateColorAsState(
        targetValue = if (highlight) MaterialTheme.colorScheme.primary else Color.Gray,
        animationSpec = tween(durationMillis = 300)
    )

    val elevation by animateDpAsState(
        targetValue = if (highlight) 8.dp else 2.dp,
        animationSpec = tween(300)
    )

    val alpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.4f,
        animationSpec = tween(300)
    )

    Card(
        modifier = modifier
            .size(width = 60.dp, height = 90.dp)
            .graphicsLayer { this.alpha = alpha }
            .clickable(
                enabled = enabled,
                onClick = onClick
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        border = BorderStroke(2.dp, borderColor),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Text(
                text = "${card.rank.displayName}${getSuitSymbol(card.suit)}",
                color = getSuitColor(card.suit),
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
        }
    }
}
