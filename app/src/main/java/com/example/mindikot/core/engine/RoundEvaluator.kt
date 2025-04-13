package com.example.mindikot.core.engine

import com.example.mindikot.core.model.*
import com.example.mindikot.core.state.GameState

object RoundEvaluator {

    /**
     * Data class to hold the result of a round evaluation.
     * @property winningTeam The team that won the round (can be null if draw).
     * @property isKot True if the win was due to collecting all four Tens.
     */
    data class RoundResult(val winningTeam: Team?, val isKot: Boolean)

    /**
     * Evaluates the end of a round: checks for Kot, then majority of tens, then trick tie-breaker.
     * This function is pure and reads the immutable GameState.
     *
     * @param state The final GameState at the end of the round.
     * @return RoundResult indicating the winning team (or null for draw) and whether it was a Kot
     * win.
     */
    fun evaluateRound(state: GameState): RoundResult {
        val teams = state.teams // Immutable list
        println("Evaluating round end...") // Logging

        // 1. Instant Kot Check
        teams.find { it.hasKot() }?.let { winningTeam ->
            println("Team ${winningTeam.id} wins round by KOT!") // Logging
            return RoundResult(winningTeam, true)
        }

        // 2. Count Tens for each team (uses immutable teams/cards)
        val teamTensCount = teams.associate { team -> team.id to team.countTens() }
        println("Tens collected: $teamTensCount") // Logging

        // 3. Determine Team with Most Tens
        val maxTens = teamTensCount.values.maxOrNull() ?: 0
        val teamsWithMaxTensIds = teamTensCount.filterValues { it == maxTens }.keys

        // 4. Handle Winner Determination
        if (teamsWithMaxTensIds.size == 1) {
            // One team has clear majority of tens
            val winningTeamId = teamsWithMaxTensIds.first()
            val winningTeam = teams.first { it.id == winningTeamId } // Find team by ID
            println(
                    "Team ${winningTeam.id} wins round with majority of tens ($maxTens)."
            ) // Logging
            return RoundResult(winningTeam, false) // Not Kot
        } else if (teamsWithMaxTensIds.size > 1 && maxTens > 0) { // Only apply tie-breaker if tens were actually tied (>0)
            // Tie in tens - apply trick tie-breaker
            println("Tie in tens ($maxTens each). Applying trick tie-breaker.") // Logging
            val teamTricksWon = state.tricksWon // Get tricks won map from GameState (immutable)
            val tiedTeamsTrickCounts = teamTricksWon.filterKeys { it in teamsWithMaxTensIds }
            println("Tricks won by tied teams: $tiedTeamsTrickCounts") // Logging

            val maxTricks =
                    tiedTeamsTrickCounts.values.maxOrNull()
                            ?: -1 // Use -1 to detect no tricks won case

            // If maxTricks is 0 or less, it's still a draw (no one won tricks among tied teams)
            if (maxTricks <= 0) {
                 println("Round is a DRAW (tied tens, no decisive tricks won among tied teams).") // Logging
                 return RoundResult(null, false) // Indicate draw
            }


            val teamsWithMaxTricks = tiedTeamsTrickCounts.filterValues { it == maxTricks }.keys

            if (teamsWithMaxTricks.size == 1) {
                // One team won more tricks among the tied teams
                val winningTeamId = teamsWithMaxTricks.first()
                val winningTeam = teams.first { it.id == winningTeamId } // Find team by ID
                println(
                        "Team ${winningTeam.id} wins round due to trick tie-breaker ($maxTricks tricks)."
                ) // Logging
                return RoundResult(winningTeam, false) // Not Kot
            } else {
                // Still tied (same number of tens AND same number of tricks among those tied) -> Draw
                println("Round is a DRAW (tied tens and tricks among top teams).") // Logging
                return RoundResult(null, false) // Indicate draw
            }
        } else {
            // No tens collected by anyone, or some other edge case? Treat as draw.
            println(
                    "Round evaluation resulted in no winner (likely no tens collected or tie with 0 tens). Treating as Draw."
            ) // Logging
            return RoundResult(null, false)
        }
    }
}