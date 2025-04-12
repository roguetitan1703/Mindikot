package com.example.mindikot.core.engine

import com.example.mindikot.core.model.*

object RoundEvaluator {
    data class RoundResult(val winningTeam: Team, val isKot: Boolean)

    /**
     * Evaluate end-of-round: if any team has all 4 tens → Kot, else majority of tens.
     */
    fun evaluateRound(teams: List<Team>): RoundResult {
        // Instant Kot
        teams.find { it.hasKot() }?.let { return RoundResult(it, true) }

        // Otherwise pick team with most tens
        val winner = teams.maxBy { it.countTens() }
        return RoundResult(winner, false)
    }
}
