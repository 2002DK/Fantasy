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
 * A team's expected best-lineup score in future weeks. Football uses weekly
 * projections in the league's scoring (byes count as 0 because a player has no
 * projection that week); basketball supplies each player's weekly Lock-In value and
 * its variance. Values are computed once per player and week, so evaluating many
 * hypothetical rosters (the trade finder) stays fast.
 */
public final class TeamProjector {

    public record WeekProjection(double mean, double variance) {

        public static final WeekProjection NONE = new WeekProjection(0, 0);
    }

    /** One player's expected score and its variance in one week. */
    @FunctionalInterface
    public interface PlayerWeeks {

        WeekProjection project(PlayerSummary player, int week);
    }

    private final List<String> slots;
    private final int fromWeek;
    private final int toWeek;
    /** player ID -> per-week expectation and variance, index 0 = fromWeek */
    private final Map<String, WeekProjection[]> weeks = new HashMap<>();

    /** Football: weekly projections with the usual spread (sd = half the projection). */
    public TeamProjector(List<String> startingSlots, SeasonValuer.Season season, int fromWeek, int toWeek,
            Collection<PlayerSummary> players) {
        this(startingSlots, fromWeek, toWeek, players, (player, week) -> {
            Double projected = SeasonValuer.projectedPoints(player, week, season);
            double mean = projected != null ? projected : 0;
            return new WeekProjection(mean, ScoreDistribution.playerVariance(mean));
        });
    }

    public TeamProjector(List<String> startingSlots, int fromWeek, int toWeek, Collection<PlayerSummary> players,
            PlayerWeeks model) {
        this.slots = startingSlots;
        this.fromWeek = fromWeek;
        this.toWeek = toWeek;
        for (PlayerSummary player : players) {
            WeekProjection[] perWeek = new WeekProjection[Math.max(0, toWeek - fromWeek + 1)];
            for (int week = fromWeek; week <= toWeek; week++) {
                perWeek[week - fromWeek] = model.project(player, week);
            }
            weeks.put(player.playerId(), perWeek);
        }
    }

    public int fromWeek() {
        return fromWeek;
    }

    public int toWeek() {
        return toWeek;
    }

    public double points(PlayerSummary player, int week) {
        return projection(player, week).mean();
    }

    private WeekProjection projection(PlayerSummary player, int week) {
        WeekProjection[] perWeek = weeks.get(player.playerId());
        return perWeek == null || week < fromWeek || week > toWeek ? WeekProjection.NONE : perWeek[week - fromWeek];
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
                WeekProjection projection = projection(c.player(), week);
                mean += projection.mean();
                variance += projection.variance();
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
