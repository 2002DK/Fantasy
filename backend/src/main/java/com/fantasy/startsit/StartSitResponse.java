package com.fantasy.startsit;

import java.util.List;

import com.fantasy.lockin.LockInScorer;
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
            Double score,
            /** Basketball only: the rest of the week under Lock-In; null for football. */
            LockInDetail lockIn) {

        public PlayerAnalysis(PlayerSummary player, boolean available, String availabilityNote,
                Double projectedPoints, Double recentAverage, List<RecentGame> recentGames, Matchup matchup,
                Double score) {
            this(player, available, availabilityNote, projectedPoints, recentAverage, recentGames, matchup, score,
                    null);
        }
    }

    /**
     * A basketball player's remaining games this week. {@code projectedPoints} and
     * {@code recentAverage} above are per game, and {@code score} is the Lock-In value.
     * {@code thresholds} line up with {@code games}: after each game, lock a score above
     * it; the last game's is 0 because it counts if nothing was locked.
     */
    public record LockInDetail(List<LockInScorer.GameOutlook> games, List<Double> thresholds) {
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
