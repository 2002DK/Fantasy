package com.fantasy.startsit;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fantasy.sleeper.SleeperWeeklyEntry;

/**
 * How many fantasy points each NFL defense has allowed per game to each position,
 * averaged over completed weeks. Built from the fan_pts_allow_&lt;pos&gt; stats on team
 * defense rows. Only relative standing is used, so the scoring system Sleeper uses
 * for these totals does not matter.
 */
final class DefenseTable {

    record Standing(double allowedPerGame, double leagueAverage, int rank, int teams) {
    }

    private static final String ALLOWED_PREFIX = "fan_pts_allow_";

    /** position (lowercase) -> team -> average points allowed per game */
    private final Map<String, Map<String, Double>> averages;

    private DefenseTable(Map<String, Map<String, Double>> averages) {
        this.averages = averages;
    }

    static DefenseTable from(List<List<SleeperWeeklyEntry>> weeks) {
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

    /** Rank 1 = the defense that allows the most points to this position (the easiest matchup). */
    Optional<Standing> standing(String defenseTeam, String position) {
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
