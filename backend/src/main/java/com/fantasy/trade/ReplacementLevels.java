package com.fantasy.trade;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What a replacement-level player at each position is worth in one league: the
 * rest-of-season projection of the best player who would not start, given the
 * league's team count and starting slots. A player's trade value is their points
 * above this level, which is why a QB in a one-QB league is worth less than their
 * raw points suggest.
 */
final class ReplacementLevels {

    /** How flex slots are typically filled, as a share of each slot per position. */
    private static final Map<String, Map<String, Double>> FLEX_SHARES = Map.of(
            "FLEX", Map.of("RB", 0.45, "WR", 0.45, "TE", 0.10),
            "WRRB_FLEX", Map.of("RB", 0.5, "WR", 0.5),
            "REC_FLEX", Map.of("WR", 0.8, "TE", 0.2),
            "SUPER_FLEX", Map.of("QB", 0.9, "RB", 0.05, "WR", 0.05));

    private final Map<String, Double> startersPerTeam;
    private final Map<String, Double> levels;

    private ReplacementLevels(Map<String, Double> startersPerTeam, Map<String, Double> levels) {
        this.startersPerTeam = startersPerTeam;
        this.levels = levels;
    }

    /**
     * @param rosByPosition rest-of-season projected points of every projected player, by position
     */
    static ReplacementLevels compute(List<String> rosterPositions, int teams, Map<String, List<Double>> rosByPosition) {
        Map<String, Double> starters = startersPerTeam(rosterPositions);
        Map<String, Double> levels = new HashMap<>();
        starters.forEach((position, perTeam) -> {
            int startersInLeague = (int) Math.round(perTeam * teams);
            List<Double> ranked = rosByPosition.getOrDefault(position, List.of()).stream()
                    .sorted(Comparator.reverseOrder())
                    .toList();
            levels.put(position, startersInLeague < ranked.size() ? ranked.get(startersInLeague) : 0.0);
        });
        return new ReplacementLevels(starters, levels);
    }

    static Map<String, Double> startersPerTeam(List<String> rosterPositions) {
        Map<String, Double> starters = new HashMap<>();
        for (String slot : rosterPositions) {
            Map<String, Double> shares = FLEX_SHARES.getOrDefault(slot, Map.of(slot, 1.0));
            shares.forEach((position, share) -> starters.merge(position, share, Double::sum));
        }
        starters.remove("BN");
        starters.remove("IR");
        return starters;
    }

    /** Whether the league has any starting slot this position can fill. */
    boolean isStartable(String position) {
        return position != null && startersPerTeam.getOrDefault(position, 0.0) > 0;
    }

    double level(String position) {
        return position != null ? levels.getOrDefault(position, 0.0) : 0.0;
    }
}
