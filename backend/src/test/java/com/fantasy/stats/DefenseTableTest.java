package com.fantasy.stats;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fantasy.sleeper.SleeperWeeklyEntry;

class DefenseTableTest {

    @Test
    void averagesPointsAllowedAcrossWeeksAndRanksMostAllowedFirst() {
        // Per-game averages: NYJ 28, DAL 21, SF 11 -> league average 20
        DefenseTable table = DefenseTable.from(List.of(
                List.of(defense("NYJ", 30), defense("SF", 10), defense("DAL", 21), player("4866")),
                List.of(defense("NYJ", 26), defense("SF", 12), defense("DAL", 21))));

        assertThat(table.standing("NYJ", "RB")).get()
                .isEqualTo(new DefenseTable.Standing(28, 20, 1, 3));
        assertThat(table.standing("SF", "rb")).get()
                .extracting(DefenseTable.Standing::rank).isEqualTo(3);
    }

    @Test
    void unknownTeamOrPositionHasNoStanding() {
        DefenseTable table = DefenseTable.from(List.of(List.of(defense("NYJ", 30))));

        assertThat(table.standing("SF", "RB")).isEmpty();
        assertThat(table.standing("NYJ", "QB")).isEmpty();
        assertThat(table.standing("NYJ", null)).isEmpty();
        assertThat(DefenseTable.from(List.of()).standing("NYJ", "RB")).isEmpty();
    }

    private static SleeperWeeklyEntry defense(String team, double allowedToRbs) {
        return new SleeperWeeklyEntry(team, team, "X", 1,
                Map.of("gp", 1, "fan_pts_allow", 90, "fan_pts_allow_rb", allowedToRbs));
    }

    private static SleeperWeeklyEntry player(String id) {
        return new SleeperWeeklyEntry(id, "PHI", "DAL", 1, Map.of("gp", 1, "rush_yd", 100));
    }
}
