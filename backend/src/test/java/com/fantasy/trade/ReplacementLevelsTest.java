package com.fantasy.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ReplacementLevelsTest {

    @Test
    void countsFlexSlotsFractionally() {
        Map<String, Double> starters = ReplacementLevels.startersPerTeam(
                List.of("QB", "RB", "RB", "WR", "WR", "TE", "FLEX", "WRRB_FLEX", "K", "DEF", "BN", "BN", "IR"));

        assertThat(starters.get("QB")).isEqualTo(1.0);
        assertThat(starters.get("RB")).isCloseTo(2.95, within(1e-9)); // 2 + 0.45 + 0.5
        assertThat(starters.get("WR")).isCloseTo(2.95, within(1e-9));
        assertThat(starters.get("TE")).isCloseTo(1.1, within(1e-9));
        assertThat(starters).doesNotContainKeys("BN", "IR", "FLEX", "WRRB_FLEX");
    }

    @Test
    void replacementIsBestPlayerOutsideLeagueStarters() {
        // 3 teams x 1 QB = 3 starters; the 4th best QB is replacement level
        ReplacementLevels levels = ReplacementLevels.compute(List.of("QB", "BN"), 3,
                Map.of("QB", List.of(200.0, 300.0, 250.0, 180.0, 150.0)));

        assertThat(levels.level("QB")).isEqualTo(180.0);
        assertThat(levels.isStartable("QB")).isTrue();
        assertThat(levels.isStartable("K")).isFalse();
        assertThat(levels.level("K")).isZero();
    }

    @Test
    void fewerProjectedPlayersThanStartersMeansZeroReplacement() {
        ReplacementLevels levels = ReplacementLevels.compute(List.of("TE"), 12, Map.of("TE", List.of(90.0, 80.0)));

        assertThat(levels.level("TE")).isZero();
    }
}
