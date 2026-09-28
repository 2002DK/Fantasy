package com.fantasy.startsit;

import java.util.Map;

import com.fantasy.sleeper.SleeperWeeklyEntry;

/** Scores a stat line with a league's own scoring settings. */
final class ScoringCalculator {

    private ScoringCalculator() {
    }

    /**
     * Sleeper stat keys and scoring keys share names, so points are the sum of
     * stat × points-per-unit. This covers bonuses and defense point-allowed buckets
     * too, since those appear as stats (e.g. pts_allow_14_20: 1).
     */
    static double points(SleeperWeeklyEntry entry, Map<String, Double> scoring) {
        double total = 0;
        for (Map.Entry<String, Double> rule : scoring.entrySet()) {
            total += entry.stat(rule.getKey()) * rule.getValue();
        }
        return round(total);
    }

    static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
