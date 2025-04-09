package com.example.mindikot.core.engine

import com.example.mindikot.core.model.Rank
import com.example.mindikot.core.model.Team

data class RoundResult(
    val winningTeam: Team,
    val points: Int,
    val isKot: Boolean
)

object RoundEvaluator {
    fun evaluateRound(teams: List<Team>): RoundResult {
        val tensCount = teams.associateWith { team ->
            team.capturedCards.count { it.rank == Rank.TEN }
        }
        val (winningTeam, count) = tensCount.maxByOrNull { it.value }!!
        val isKot = count == 4
        val points = 1
        winningTeam.score += points
        return RoundResult(winningTeam, points, isKot)
    }
}
