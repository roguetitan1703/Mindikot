package com.example.mindikot.ui.components

import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items // <-- Ensure this import is added
import androidx.compose.runtime.Composable
import com.example.mindikot.core.model.Card

@Composable
fun PlayerHand(cards: List<Card>, onCardSelected: (Card) -> Unit) {
    LazyRow {
        items(cards) { card -> // Use the items function that works with a list of objects
            CardView(card = card, onCardSelected = onCardSelected)
        }
    }
}
