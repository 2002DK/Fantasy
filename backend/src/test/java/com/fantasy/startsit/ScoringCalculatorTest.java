package com.fantasy.startsit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fantasy.sleeper.SleeperWeeklyEntry;

class ScoringCalculatorTest {

    @Test
    void appliesLeagueScoringToStatLine() {
        // Half PPR with 6-point passing TDs; pts_ppr on the line must be ignored
        Map<String, Double> scoring = Map.of("pass_yd", 0.04, "pass_td", 6.0, "pass_int", -2.0,
                "rush_yd", 0.1, "rec", 0.5, "rec_yd", 0.1);
        SleeperWeeklyEntry line = entry(Map.of("pass_yd", 246.52, "pass_td", 1.82, "pass_int", 0.98,
                "rush_yd", 10.15, "pts_ppr", 17.48, "gp", 1));

        // 9.8608 + 10.92 - 1.96 + 1.015 = 19.8358
        assertThat(ScoringCalculator.points(line, scoring)).isEqualTo(19.84);
    }

    @Test
    void scoresDefensePointsAllowedBuckets() {
        Map<String, Double> scoring = Map.of("sack", 1.0, "int", 2.0, "pts_allow_21_27", 0.0,
                "pts_allow_14_20", 1.0, "yds_allow_350_399", -1.0);
        SleeperWeeklyEntry line = entry(Map.of("sack", 3, "int", 1, "pts_allow_14_20", 1,
                "yds_allow_350_399", 1, "pts_allow", 20));

        assertThat(ScoringCalculator.points(line, scoring)).isEqualTo(5.0);
    }

    private static SleeperWeeklyEntry entry(Map<String, Object> stats) {
        return new SleeperWeeklyEntry("1", "CIN", "PIT", 3, stats);
    }
}
