package com.fantasy.stats;

/**
 * How uncertain a fantasy score is. A player's weekly score is modelled as normal
 * with a standard deviation of half their expected points (typical week-to-week
 * swing for fantasy starters); a team's variance is the sum of its players'.
 */
public final class ScoreDistribution {

    /** Standard deviation as a fraction of a player's expected points. */
    public static final double PLAYER_SD_FRACTION = 0.5;

    private ScoreDistribution() {
    }

    public static double playerVariance(double expectedPoints) {
        double sd = PLAYER_SD_FRACTION * Math.max(0, expectedPoints);
        return sd * sd;
    }

    /** P(A scores more than B) for independent normal team scores. */
    public static double winProbability(double meanA, double varianceA, double meanB, double varianceB) {
        double variance = varianceA + varianceB;
        if (variance <= 0) {
            return meanA > meanB ? 1 : meanA < meanB ? 0 : 0.5;
        }
        return normalCdf((meanA - meanB) / Math.sqrt(variance));
    }

    /** Standard normal CDF (Abramowitz and Stegun 7.1.26, error below 1.5e-7). */
    public static double normalCdf(double z) {
        double t = 1 / (1 + 0.3275911 * Math.abs(z) / Math.sqrt(2));
        double erf = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592)
                * t * Math.exp(-z * z / 2);
        return z >= 0 ? (1 + erf) / 2 : (1 - erf) / 2;
    }
}
