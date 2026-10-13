package com.fantasy.lockin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.fantasy.lockin.LockInModel.Outcome;

class LockInModelTest {

    @Test
    void matchesTheSpecTableForAFortyPointPlayer() {
        assertThat(LockInModel.weeklyValue(List.of(40.0))).isCloseTo(40.0, within(0.05));
        assertThat(LockInModel.weeklyValue(List.of(40.0, 40.0))).isCloseTo(48.0, within(0.05));
        assertThat(LockInModel.weeklyValue(List.of(40.0, 40.0, 40.0))).isCloseTo(52.6, within(0.05));
        assertThat(LockInModel.weeklyValue(List.of(40.0, 40.0, 40.0, 40.0))).isCloseTo(55.8, within(0.05));
    }

    @Test
    void lockThresholdIsTheValueOfTheGamesStillToCome() {
        Outcome outcome = LockInModel.evaluate(List.of(40.0, 40.0, 40.0));

        assertThat(outcome.thresholds()).hasSize(3);
        assertThat(outcome.thresholds().get(0)).isCloseTo(48.0, within(0.05));
        assertThat(outcome.thresholds().get(1)).isCloseTo(40.0, within(0.001));
        assertThat(outcome.thresholds().get(2)).isZero();
    }

    @Test
    void oneGameIsItsProjectionWithTheUsualSpread() {
        Outcome outcome = LockInModel.evaluate(List.of(40.0));

        assertThat(outcome.value()).isEqualTo(40.0);
        assertThat(outcome.variance()).isCloseTo(400.0, within(1e-9)); // sd = 0.5 × 40
        assertThat(outcome.thresholds()).containsExactly(0.0);
    }

    @Test
    void choosingTheBestGameNarrowsTheSpread() {
        assertThat(LockInModel.evaluate(List.of(40.0, 40.0, 40.0)).variance()).isLessThan(400.0);
    }

    @Test
    void noGamesIsWorthNothing() {
        assertThat(LockInModel.evaluate(List.of())).isEqualTo(Outcome.NO_GAMES);
    }

    @Test
    void aWeakLastGameMakesEarlyGamesWorthLockingAlmostAlways() {
        Outcome outcome = LockInModel.evaluate(List.of(40.0, 10.0));

        assertThat(outcome.thresholds().getFirst()).isEqualTo(10.0);
        assertThat(outcome.value()).isBetween(40.0, 41.0);
    }

    @Test
    void zeroProjectionsAreCertainAndNegativeOnesCountAsZero() {
        // Nothing expected tonight: always pass to the 40-point game
        assertThat(LockInModel.weeklyValue(List.of(0.0, 40.0))).isEqualTo(40.0);
        assertThat(LockInModel.weeklyValue(List.of(-5.0, 40.0))).isEqualTo(40.0);
        assertThat(LockInModel.weeklyValue(List.of(0.0, 0.0))).isZero();
    }

    @Test
    void addingAGameNeverLowersTheValue() {
        double previous = 0;
        for (int games = 1; games <= 5; games++) {
            double value = LockInModel.weeklyValue(Collections.nCopies(games, 25.0));
            assertThat(value).isGreaterThan(previous);
            previous = value;
        }
    }

    @Test
    void meanAndVarianceMatchASimulationOfTheThresholdStrategy() {
        List<Double> means = List.of(30.0, 50.0, 20.0, 35.0);
        Outcome outcome = LockInModel.evaluate(means);
        Random random = new Random(42);
        int runs = 400_000;
        double sum = 0;
        double sumOfSquares = 0;
        for (int run = 0; run < runs; run++) {
            double counted = 0;
            for (int game = 0; game < means.size(); game++) {
                double mu = means.get(game);
                counted = mu + 0.5 * mu * random.nextGaussian();
                if (counted >= outcome.thresholds().get(game)) {
                    break;
                }
            }
            sum += counted;
            sumOfSquares += counted * counted;
        }
        double mean = sum / runs;
        double variance = sumOfSquares / runs - mean * mean;

        assertThat(outcome.value()).isCloseTo(mean, within(0.1));
        assertThat(outcome.variance()).isCloseTo(variance, within(variance * 0.02));
    }
}
