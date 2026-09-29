package com.fantasy.stats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class ScoreDistributionTest {

    @Test
    void normalCdfMatchesKnownValues() {
        assertThat(ScoreDistribution.normalCdf(0)).isCloseTo(0.5, within(1e-7));
        assertThat(ScoreDistribution.normalCdf(1.96)).isCloseTo(0.975, within(1e-4));
        assertThat(ScoreDistribution.normalCdf(-1)).isCloseTo(0.1587, within(1e-4));
    }

    @Test
    void playerVarianceIsHalfTheExpectedPointsSquared() {
        assertThat(ScoreDistribution.playerVariance(20)).isEqualTo(100);
        assertThat(ScoreDistribution.playerVariance(-5)).isZero();
    }

    @Test
    void winProbabilityFromMeansAndVariances() {
        // 110 vs 100 with a combined standard deviation of 20: P = cdf(0.5) = 0.6915
        assertThat(ScoreDistribution.winProbability(110, 200, 100, 200)).isCloseTo(0.6915, within(1e-4));
        assertThat(ScoreDistribution.winProbability(90, 0, 80, 0)).isEqualTo(1);
        assertThat(ScoreDistribution.winProbability(80, 0, 80, 0)).isEqualTo(0.5);
    }
}
