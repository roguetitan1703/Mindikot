package com.example.mindikot.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.mindikot.core.model.Card
import com.example.mindikot.core.state.InputType
import com.example.mindikot.ui.components.CardView
@Composable
fun PlayerHand(
        cards: List<Card>,
        validMoves: Set<Card>, // Set of valid cards to play
        isMyTurn: Boolean, // Is it currently the local player's turn?
        requiredInputType: InputType?, // What kind of input is expected from the player
        onCardSelected: (Card) -> Unit // Callback when a card is selected
) {
    LazyRow(
            modifier = Modifier.padding(8.dp), // Optional padding around the hand
            horizontalArrangement =
                    androidx.compose.foundation.layout.Arrangement.spacedBy(
                            4.dp
                    ) // Spacing between cards
    ) {
        items(
                items = cards,
                key = { card ->
                    "${card.suit}-${card.rank}"
                } // Stable item keys for better performance
        ) { card ->
            CardView( // Use PlayerCardView now (or renamed CardView)
                    card = card,
                    isValidMove =
                            card in validMoves, // Check if this card is in the valid moves set
                    isPlayable =
                            isMyTurn &&
                                    (requiredInputType == InputType.PLAY_CARD ||
                                            requiredInputType ==
                                                    InputType
                                                            .CHOOSE_TRUMP_SUIT), // Playable if it's
                    // my turn AND
                    // Play_Card input
                    // expected
                    onCardSelected = onCardSelected
            )
        }
    }
}
