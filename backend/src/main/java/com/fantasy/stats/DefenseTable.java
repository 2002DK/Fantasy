package com.fantasy.stats;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fantasy.sleeper.SleeperWeeklyEntry;

/**
 * How many fantasy points each team's defense has allowed per game to each position.
 * Football uses the fan_pts_allow_&lt;pos&gt; stats on team defense rows (only relative
 * standing is used, so Sleeper's scoring for those totals does not matter).
 * Basketball has no such stat, so its table totals each player's points in the
 * league's scoring against their opponent, game by game.
 */
public final class DefenseTable {

    public record Standing(double allowedPerGame, double leagueAverage, int rank, int teams) {
    }

    private static final String ALLOWED_PREFIX = "fan_pts_allow_";

    /** position (lowercase) -> team -> average points allowed per game */
    private final Map<String, Map<String, Double>> averages;

    private DefenseTable(Map<String, Map<String, Double>> averages) {
        this.averages = averages;
    }

    public static DefenseTable from(List<List<SleeperWeeklyEntry>> weeks) {
        Map<String, Map<String, double[]>> totals = new HashMap<>();
        for (List<SleeperWeeklyEntry> week : weeks) {
            for (SleeperWeeklyEntry entry : week) {
                if (!entry.hasStat("fan_pts_allow") || !entry.played()) {
                    continue;
                }
                for (String key : entry.stats().keySet()) {
                    if (key.startsWith(ALLOWED_PREFIX)) {
                        double[] sumAndGames = totals
                                .computeIfAbsent(key.substring(ALLOWED_PREFIX.length()), k -> new HashMap<>())
                                .computeIfAbsent(entry.playerId(), k -> new double[2]);
                        sumAndGames[0] += entry.stat(key);
                        sumAndGames[1]++;
                    }
                }
            }
        }
        Map<String, Map<String, Double>> averages = new HashMap<>();
        totals.forEach((position, byTeam) -> {
            Map<String, Double> teamAverages = new HashMap<>();
            byTeam.forEach((team, sumAndGames) -> teamAverages.put(team, sumAndGames[0] / sumAndGames[1]));
            averages.put(position, teamAverages);
        });
        return new DefenseTable(averages);
    }

    /**
     * Basketball: points allowed per game to each primary position, from box scores.
     *
     * @param positions player ID -> primary position; players without one are skipped
     */
    public static DefenseTable fromBoxScores(List<SleeperWeeklyEntry> games, Map<String, String> positions,
            Map<String, Double> scoring) {
        // position -> team -> points allowed; team -> games played
        Map<String, Map<String, Double>> allowed = new HashMap<>();
        Map<String, Set<String>> gamesByTeam = new HashMap<>();
        for (SleeperWeeklyEntry entry : games) {
            String position = positions.get(entry.playerId());
            if (!entry.played() || entry.opponent() == null || position == null) {
                continue;
            }
            String game = entry.gameId() != null ? entry.gameId() : entry.date() + "/" + entry.team();
            gamesByTeam.computeIfAbsent(entry.opponent(), t -> new HashSet<>()).add(game);
            allowed.computeIfAbsent(position.toLowerCase(), p -> new HashMap<>())
                    .merge(entry.opponent(), ScoringCalculator.points(entry, scoring), Double::sum);
        }
        Map<String, Map<String, Double>> averages = new HashMap<>();
        allowed.forEach((position, byTeam) -> {
            Map<String, Double> teamAverages = new HashMap<>();
            byTeam.forEach((team, points) -> teamAverages.put(team, points / gamesByTeam.get(team).size()));
            averages.put(position, teamAverages);
        });
        return new DefenseTable(averages);
    }

    /** Rank 1 = the defense that allows the most points to this position (the easiest matchup). */
    public Optional<Standing> standing(String defenseTeam, String position) {
        if (position == null) {
            return Optional.empty();
        }
        Map<String, Double> byTeam = averages.get(position.toLowerCase());
        if (byTeam == null || !byTeam.containsKey(defenseTeam)) {
            return Optional.empty();
        }
        List<String> ranked = byTeam.keySet().stream()
                .sorted(Comparator.comparing(byTeam::get).reversed())
                .toList();
        double leagueAverage = byTeam.values().stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return Optional.of(new Standing(byTeam.get(defenseTeam), leagueAverage,
                ranked.indexOf(defenseTeam) + 1, ranked.size()));
    }
}
