package com.example.mindikot.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.mindikot.core.model.Card

@Composable
fun CardView(card: Card, onCardSelected: (Card) -> Unit) {
    Card(
        modifier = Modifier
            .padding(8.dp)
            .clickable { onCardSelected(card) },
        shape = MaterialTheme.shapes.medium
    ) {
        Text(
            text = "${card.rank} of ${card.suit}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp)
        )
    }
}
