package com.fantasy.lineup;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fantasy.lineup.LineupSolver.Candidate;
import com.fantasy.player.PlayerSummary;
import com.fantasy.stats.ScoreDistribution;
import com.fantasy.trade.SeasonValuer;

/**
 * A team's expected best-lineup score in future weeks, from weekly projections in the
 * league's scoring. Byes count as 0 because a player has no projection that week.
 * Projected points are computed once per player and week, so evaluating many
 * hypothetical rosters (the trade finder) stays fast.
 */
public final class TeamProjector {

    public record WeekProjection(double mean, double variance) {
    }

    private final List<String> slots;
    private final int fromWeek;
    private final int toWeek;
    /** player ID -> projected points per week, index 0 = fromWeek */
    private final Map<String, double[]> points = new HashMap<>();

    public TeamProjector(List<String> startingSlots, SeasonValuer.Season season, int fromWeek, int toWeek,
            Collection<PlayerSummary> players) {
        this.slots = startingSlots;
        this.fromWeek = fromWeek;
        this.toWeek = toWeek;
        for (PlayerSummary player : players) {
            double[] weekly = new double[Math.max(0, toWeek - fromWeek + 1)];
            for (int week = fromWeek; week <= toWeek; week++) {
                Double projected = SeasonValuer.projectedPoints(player, week, season);
                weekly[week - fromWeek] = projected != null ? projected : 0;
            }
            points.put(player.playerId(), weekly);
        }
    }

    public int fromWeek() {
        return fromWeek;
    }

    public int toWeek() {
        return toWeek;
    }

    public double points(PlayerSummary player, int week) {
        double[] weekly = points.get(player.playerId());
        return weekly == null || week < fromWeek || week > toWeek ? 0 : weekly[week - fromWeek];
    }

    /** Best-lineup expected points and variance for these players in one week. */
    public WeekProjection week(List<PlayerSummary> players, int week) {
        List<Candidate> candidates = new ArrayList<>(players.size());
        for (PlayerSummary player : players) {
            candidates.add(new Candidate(player, points(player, week), false));
        }
        List<Candidate> lineup = LineupSolver.solve(slots, List.of(), candidates);
        double mean = 0;
        double variance = 0;
        for (Candidate c : lineup) {
            if (c != null) {
                mean += c.points();
                variance += ScoreDistribution.playerVariance(c.points());
            }
        }
        return new WeekProjection(mean, variance);
    }

    /** Sum of weekly best-lineup points from {@code fromWeek} through {@code toWeek}. */
    public double restOfSeason(List<PlayerSummary> players) {
        double total = 0;
        for (int week = fromWeek; week <= toWeek; week++) {
            total += week(players, week).mean();
        }
        return total;
    }

    public int weeks() {
        return Math.max(0, toWeek - fromWeek + 1);
    }

}
