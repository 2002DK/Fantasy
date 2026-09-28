package com.fantasy.startsit;

import java.util.List;

import com.fantasy.player.PlayerSummary;

/**
 * A start/sit comparison of two players for one week. All points use the league's
 * own scoring settings. {@code recommendation} is null when there is not enough data
 * to choose; {@code notes} explains which data was unavailable.
 */
public record StartSitResponse(
        String season,
        int week,
        Recommendation recommendation,
        List<PlayerAnalysis> players,
        List<String> reasons,
        List<String> notes) {

    public enum Confidence {
        /** Scores within 5%: either choice is defensible. */
        TOSS_UP,
        /** Scores within 15%. */
        LEAN,
        CLEAR
    }

    public record Recommendation(String playerId, Confidence confidence, double marginPercent) {
    }

    public record PlayerAnalysis(
            PlayerSummary player,
            boolean available,
            /** Why the player cannot start or carries risk, e.g. "Out", "On bye", "Questionable". */
            String availabilityNote,
            Double projectedPoints,
            Double recentAverage,
            List<RecentGame> recentGames,
            Matchup matchup,
            /** Blended score used for the decision; 0 when unavailable, null when no data. */
            Double score) {
    }

    public record RecentGame(int week, String opponent, double points) {
    }

    /**
     * {@code rank} 1 = the opponent allows the most points to this position (easiest);
     * null when there is no completed-week data yet.
     */
    public record Matchup(String opponent, boolean home, Integer rank, Integer teams,
            Double allowedPerGame, Double leagueAverage) {
    }
}
