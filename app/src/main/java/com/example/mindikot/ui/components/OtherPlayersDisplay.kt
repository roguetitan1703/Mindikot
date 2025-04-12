package com.example.mindikot.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person // Or style_outlined depending on preference
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mindikot.core.model.Player

@Composable
fun OtherPlayerDisplay(player: Player, isCurrentTurn: Boolean, modifier: Modifier = Modifier) {
    val teamColor =
            if (player.teamId == 1) Color(0xFFADD8E6)
            else Color(0xFFFAC898) // Example Light Blue/Peach
    val turnIndicatorColor =
            if (isCurrentTurn) MaterialTheme.colorScheme.primary else Color.Transparent
    val nameColor =
            if (isCurrentTurn) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface

    Column(
            modifier =
                    modifier.padding(8.dp)
                            .border(
                                    2.dp,
                                    turnIndicatorColor,
                                    CircleShape
                            ) // Highlight border if turn
                            .padding(8.dp), // Inner padding
            horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
                imageVector = Icons.Default.Person,
                contentDescription = player.name,
                modifier =
                        Modifier.size(40.dp)
                                .clip(CircleShape)
                                .background(teamColor.copy(alpha = 0.5f))
                                .padding(4.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
                text = player.name,
                fontWeight = if (isCurrentTurn) FontWeight.Bold else FontWeight.Normal,
                fontSize = 14.sp,
                color = nameColor
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
                text = "Cards: ${player.hand.size}", // Display card count
                fontSize = 12.sp,
                color = Color.Gray
        )
    }
}
