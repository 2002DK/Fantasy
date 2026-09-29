package com.fantasy.standings;

import java.util.List;

/**
 * League standings with power rankings and playoff odds. {@code simulations},
 * {@code playoffOdds} and {@code projectedWins} are null when odds could not be
 * simulated (e.g. projections unavailable or the regular season is over).
 */
public record StandingsResponse(
        int week,
        int regularSeasonEnd,
        int playoffTeams,
        Integer simulations,
        List<TeamStanding> teams,
        List<String> notes) {

    /**
     * {@code strength} is the team's expected weekly starting-lineup points for the
     * rest of the season; {@code powerRank} orders teams by it. Playoff odds are percent.
     */
    public record TeamStanding(int rank, int rosterId, String teamName, String ownerName, String avatarUrl,
            int wins, int losses, int ties, double pointsFor, double strength, int powerRank,
            Double playoffOdds, Double projectedWins, boolean you) {
    }
}
