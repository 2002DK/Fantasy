package com.fantasy.standings;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.stream.IntStream;

/**
 * Monte Carlo simulation of the rest of a fantasy regular season.
 *
 * <p>Each week every team's score is drawn from a normal distribution around its
 * expected lineup total. Head-to-head pairings decide wins; in leagues that also play
 * the weekly median, each team additionally wins if it beats the median score.
 * Final standings sort by wins, then points for, and the top {@code playoffTeams}
 * make the playoffs.
 */
public final class SeasonSimulator {

    public record TeamStart(int rosterId, double wins, double pointsFor) {
    }

    /** One week: head-to-head pairs of roster IDs, and each team's expected score and variance. */
    public record WeekGames(List<int[]> pairings, Map<Integer, Double> means, Map<Integer, Double> variances) {
    }

    public record Result(Map<Integer, Double> playoffOdds, Map<Integer, Double> averageWins) {
    }

    private SeasonSimulator() {
    }

    public static Result simulate(List<TeamStart> teams, List<WeekGames> weeks, int playoffTeams, boolean median,
            int simulations, long seed) {
        int n = teams.size();
        Map<Integer, Integer> index = new HashMap<>();
        for (int i = 0; i < n; i++) {
            index.put(teams.get(i).rosterId(), i);
        }
        double[] startWins = teams.stream().mapToDouble(TeamStart::wins).toArray();
        double[] startPoints = teams.stream().mapToDouble(TeamStart::pointsFor).toArray();

        // Per week, per team: mean and standard deviation (0 for teams not in the league map)
        double[][] means = new double[weeks.size()][n];
        double[][] sds = new double[weeks.size()][n];
        for (int w = 0; w < weeks.size(); w++) {
            for (var e : weeks.get(w).means().entrySet()) {
                Integer i = index.get(e.getKey());
                if (i != null) {
                    means[w][i] = e.getValue();
                    sds[w][i] = Math.sqrt(weeks.get(w).variances().getOrDefault(e.getKey(), 0.0));
                }
            }
        }

        SplittableRandom random = new SplittableRandom(seed);
        int[] playoffCounts = new int[n];
        double[] winTotals = new double[n];
        double[] wins = new double[n];
        double[] points = new double[n];
        double[] scores = new double[n];
        Integer[] order = new Integer[n];
        int cutoff = Math.min(playoffTeams, n);

        for (int s = 0; s < simulations; s++) {
            System.arraycopy(startWins, 0, wins, 0, n);
            System.arraycopy(startPoints, 0, points, 0, n);
            for (int w = 0; w < weeks.size(); w++) {
                for (int i = 0; i < n; i++) {
                    scores[i] = Math.max(0, means[w][i] + sds[w][i] * gaussian(random));
                    points[i] += scores[i];
                }
                for (int[] pair : weeks.get(w).pairings()) {
                    Integer a = index.get(pair[0]);
                    Integer b = index.get(pair[1]);
                    if (a == null || b == null) {
                        continue;
                    }
                    if (scores[a] > scores[b]) {
                        wins[a]++;
                    } else if (scores[b] > scores[a]) {
                        wins[b]++;
                    } else {
                        wins[a] += 0.5;
                        wins[b] += 0.5;
                    }
                }
                if (median) {
                    double medianScore = median(scores);
                    for (int i = 0; i < n; i++) {
                        if (scores[i] > medianScore) {
                            wins[i]++;
                        }
                    }
                }
            }
            for (int i = 0; i < n; i++) {
                order[i] = i;
                winTotals[i] += wins[i];
            }
            Arrays.sort(order, (x, y) -> wins[x] != wins[y] ? Double.compare(wins[y], wins[x])
                    : Double.compare(points[y], points[x]));
            for (int r = 0; r < cutoff; r++) {
                playoffCounts[order[r]]++;
            }
        }

        Map<Integer, Double> odds = new HashMap<>();
        Map<Integer, Double> averageWins = new HashMap<>();
        IntStream.range(0, n).forEach(i -> {
            odds.put(teams.get(i).rosterId(), (double) playoffCounts[i] / simulations);
            averageWins.put(teams.get(i).rosterId(), winTotals[i] / simulations);
        });
        return new Result(odds, averageWins);
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int mid = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
    }

    /** Standard normal sample (Box-Muller); SplittableRandom has no nextGaussian of its own. */
    private static double gaussian(SplittableRandom random) {
        double u1 = random.nextDouble();
        double u2 = random.nextDouble();
        return Math.sqrt(-2 * Math.log(1 - u1)) * Math.cos(2 * Math.PI * u2);
    }
}
