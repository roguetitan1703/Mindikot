package com.example.mindikot.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mindikot.core.model.Card

@Composable
fun CardView(
        card: Card,
        isValidMove: Boolean, // Can this card be played now?
        isPlayable: Boolean, // Is it generally the player's turn to play any card?
        onCardSelected: (Card) -> Unit,
        modifier: Modifier = Modifier
) {
    val elevation = if (isValidMove) 8.dp else 2.dp
    val alpha = if (isPlayable && !isValidMove) 0.6f else 1.0f // Dim invalid cards when it's turn
    val borderColor = if (isValidMove) MaterialTheme.colorScheme.primary else Color.Gray

    Card(
            modifier =
                    modifier.size(width = 70.dp, height = 100.dp) // Example fixed size
                            .padding(horizontal = 2.dp, vertical = 4.dp)
                            .alpha(alpha)
                            .clickable(
                                    enabled =
                                            isValidMove &&
                                                    isPlayable, // Clickable only if it's a valid
                                    // move AND it's our turn
                                    onClick = { onCardSelected(card) }
                            ),
            elevation = CardDefaults.cardElevation(defaultElevation = elevation),
            border = BorderStroke(if (isValidMove) 2.dp else 0.5.dp, borderColor),
            shape = MaterialTheme.shapes.medium // Or RoundedCornerShape(8.dp)
    ) {
        // Basic card representation (improve this visually)
        Box(modifier = Modifier.padding(4.dp), contentAlignment = Alignment.Center) {
            Text(
                    text = "${card.rank.displayName}${getSuitSymbol(card.suit)}",
                    fontSize = 18.sp, // Adjust size
                    fontWeight = FontWeight.Bold,
                    color = getSuitColor(card.suit)
            )
            // You can add separate Texts for top-left/bottom-right if needed
        }
    }
}

// Helper functions for visual representation (customize as needed)
@Composable
fun getSuitColor(suit: com.example.mindikot.core.model.Suit): Color {
    return when (suit) {
        com.example.mindikot.core.model.Suit.HEARTS,
        com.example.mindikot.core.model.Suit.DIAMONDS -> Color.Red
        com.example.mindikot.core.model.Suit.CLUBS, com.example.mindikot.core.model.Suit.SPADES ->
                Color.Black
    }
}

fun getSuitSymbol(suit: com.example.mindikot.core.model.Suit): String {
    return when (suit) {
        com.example.mindikot.core.model.Suit.HEARTS -> "♥"
        com.example.mindikot.core.model.Suit.DIAMONDS -> "♦"
        com.example.mindikot.core.model.Suit.CLUBS -> "♣"
        com.example.mindikot.core.model.Suit.SPADES -> "♠"
    }
}
