package com.fantasy.stats;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

import com.fantasy.sleeper.SleeperWeeklyEntry;

/** Scores a stat line with a league's own scoring settings. */
public final class ScoringCalculator {

    private ScoringCalculator() {
    }

    /**
     * Sleeper stat keys and scoring keys share names, so points are the sum of
     * stat × points-per-unit. This covers bonuses and defense point-allowed buckets
     * too, since those appear as stats (e.g. pts_allow_14_20: 1).
     */
    public static double points(SleeperWeeklyEntry entry, Map<String, Double> scoring) {
        double total = 0;
        for (Map.Entry<String, Double> rule : scoring.entrySet()) {
            total += entry.stat(rule.getKey()) * rule.getValue();
        }
        return round(total);
    }

    public static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }

    /**
     * One decimal, rounding half up from the number as written (47.05 -> "47.1"), as the
     * frontend does, so explanations match the points shown beside them.
     */
    public static String oneDecimal(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).toPlainString();
    }
}
