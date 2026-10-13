package com.fantasy.lockin;

import java.util.ArrayList;
import java.util.List;

import com.fantasy.stats.ScoreDistribution;

/**
 * The value of a starter's week in a Lock-In league, where one game per starter
 * counts: after each game you lock its score in or pass, and if you never lock,
 * the last game counts.
 *
 * <p>The best strategy locks a score when it beats the expected value of the games
 * still to come. Working backwards from the last game (which must count), the value
 * of the week from game i is the expected better of game i's score and the value of
 * waiting: E[max(X_i, V_{i+1})]. With each game's score normal (mean μ, standard
 * deviation σ = 0.5μ, as elsewhere in the app) and c the value of waiting:
 *
 * <pre>E[max(X, c)] = c + (μ − c)·Φ(d) + σ·φ(d),  d = (μ − c) / σ</pre>
 *
 * For four games averaging 40 points this gives 55.8, not 160: extra games help,
 * with diminishing returns.
 */
public final class LockInModel {

    private static final double SQRT_2PI = Math.sqrt(2 * Math.PI);

    /**
     * The week's outcome under the best strategy.
     *
     * @param value      expected counted score: the player's weekly Lock-In value
     * @param variance   variance of the counted score, for win probabilities
     * @param thresholds for each game, the score worth locking: the value of the
     *                   games after it. The last game's is 0, as it counts regardless.
     */
    public record Outcome(double value, double variance, List<Double> thresholds) {

        public static final Outcome NO_GAMES = new Outcome(0, 0, List.of());
    }

    private LockInModel() {
    }

    /**
     * The best strategy over games with these expected scores, in the order they are
     * played. Negative expectations are treated as zero.
     */
    public static Outcome evaluate(List<Double> gameMeans) {
        int n = gameMeans.size();
        if (n == 0) {
            return Outcome.NO_GAMES;
        }
        // Moments of the counted score from game i on, built from the last game back
        double mean = Math.max(0, gameMeans.get(n - 1));
        double sd = ScoreDistribution.PLAYER_SD_FRACTION * mean;
        double secondMoment = mean * mean + sd * sd;
        double[] thresholds = new double[n];
        for (int i = n - 2; i >= 0; i--) {
            double threshold = mean;
            thresholds[i] = threshold;
            double mu = Math.max(0, gameMeans.get(i));
            double sigma = ScoreDistribution.PLAYER_SD_FRACTION * mu;
            double lockProbability;
            double lockedMean;
            double lockedSecondMoment;
            if (sigma == 0) {
                // A certain score: lock it only if it beats waiting
                lockProbability = mu >= threshold ? 1 : 0;
                lockedMean = mu * lockProbability;
                lockedSecondMoment = mu * mu * lockProbability;
            } else {
                // Partial moments of X over X ≥ threshold
                double a = (threshold - mu) / sigma;
                double density = Math.exp(-a * a / 2) / SQRT_2PI;
                lockProbability = 1 - ScoreDistribution.normalCdf(a);
                lockedMean = mu * lockProbability + sigma * density;
                lockedSecondMoment = (mu * mu + sigma * sigma) * lockProbability + sigma * (mu + threshold) * density;
            }
            double passProbability = 1 - lockProbability;
            mean = lockedMean + passProbability * mean;
            secondMoment = lockedSecondMoment + passProbability * secondMoment;
        }
        List<Double> thresholdList = new ArrayList<>(n);
        for (double t : thresholds) {
            thresholdList.add(t);
        }
        return new Outcome(mean, Math.max(0, secondMoment - mean * mean), List.copyOf(thresholdList));
    }

    /** The weekly Lock-In value of games with these expected scores. */
    public static double weeklyValue(List<Double> gameMeans) {
        return evaluate(gameMeans).value();
    }
}
