package com.fantasy.matchup;

import java.util.List;

import com.fantasy.player.PlayerSummary;

/**
 * This week's head-to-head: both teams' starters with expected and live points, a
 * projected final score and the user's win probability. {@code opponent} and
 * {@code winProbability} are null when the user has no opponent this week. The
 * {@code optimized} fields show the projection with the lineup optimizer's changes, and
 * are null when the current lineup is already optimal.
 */
public record MatchupResponse(int week, Side you, Side opponent, Double winProbability, Double optimizedPoints,
        Double optimizedWinProbability, List<String> notes) {

    public record Side(int rosterId, String teamName, String ownerName, String avatarUrl,
            /** Points already scored this week (live during games). */
            double actualPoints,
            /** Actual points so far plus expected points still to come. */
            double projectedPoints,
            List<Starter> starters) {
    }

    /** {@code status} is "upcoming", "live" or "final" (or "bye"/"empty"). */
    public record Starter(String slot, PlayerSummary player, double expectedPoints, double actualPoints,
            double projectedPoints, String status, String opponent) {
    }
}
