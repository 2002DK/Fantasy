package com.fantasy.lockin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fantasy.lineup.TeamProjector.WeekProjection;
import com.fantasy.lockin.LockInScorer.ScheduledGame;
import com.fantasy.lockin.LockInService.PlayerWeek;
import com.fantasy.player.PlayerSummary;
import com.fantasy.startsit.StartSitResponse.Matchup;
import com.fantasy.startsit.StartSitResponse.RecentGame;
import com.fantasy.stats.ScoringCalculator;
import com.fantasy.trade.ReplacementLevels;
import com.fantasy.trade.TradeResponse.TradePlayer;

/**
 * A basketball league's rest of season under Lock-In, week by week: each player's
 * weekly Lock-In value and its spread, summed into rest-of-season value for trades,
 * power rankings and playoff odds.
 *
 * <p>This week uses {@link LockInScorer} (games left, injuries, 60/40 projection and
 * form). Later weeks blend 70% projection with 30% recent form per game, like football's
 * season values, the form part scaled up to ±15% for each opponent's points allowed to
 * the position. Next week's projections are per game, so a projected absence counts;
 * after that, each scheduled game uses the player's season-long per-game projection.
 * Long-term absences (IR, suspension) count only the projected games of the next two
 * weeks, since a return date isn't known.
 */
public final class BasketballSeason {

    static final double PROJECTION_WEIGHT = 0.7;
    static final double FORM_WEIGHT = 0.3;
    static final double MAX_SCHEDULE_ADJUSTMENT = 0.15;
    private static final Set<String> LONG_TERM_ABSENCES = Set.of("IR", "Sus", "NA");

    /** One player's week: the Lock-In outcome, games counted and the projection-only value. */
    public record Week(int week, int games, LockInModel.Outcome outcome, double projectionValue,
            double scheduleRatioSum, int scheduleRatioCount) {
    }

    private final LockInScorer scorer;
    private final LockInScorer.Week current;
    private final int currentWeek;
    private final int lastWeek;
    /** week -> team -> games; null when the schedule couldn't be loaded. */
    private final Map<Integer, Map<String, List<ScheduledGame>>> schedule;
    private final Map<String, PlayerWeek> nextWeekProjections;
    /** player ID -> projected points per game this season, in the league's scoring. */
    private final Map<String, Double> perGameProjections;
    private final List<String> notes;
    /** Per-player weeks; concurrent because a built season is shared between requests. */
    private final Map<String, List<Week>> cache = new ConcurrentHashMap<>();
    private ReplacementLevels replacement;

    public BasketballSeason(LockInScorer scorer, LockInScorer.Week current, int currentWeek, int lastWeek,
            Map<Integer, Map<String, List<ScheduledGame>>> schedule, Map<String, PlayerWeek> nextWeekProjections,
            Map<String, Double> perGameProjections, List<String> notes) {
        this.scorer = scorer;
        this.current = current;
        this.currentWeek = currentWeek;
        this.lastWeek = lastWeek;
        this.schedule = schedule;
        this.nextWeekProjections = nextWeekProjections;
        this.perGameProjections = perGameProjections;
        this.notes = notes;
    }

    public void setReplacement(ReplacementLevels replacement) {
        this.replacement = replacement;
    }

    public int currentWeek() {
        return currentWeek;
    }

    public int lastWeek() {
        return lastWeek;
    }

    public List<String> notes() {
        return notes;
    }

    /** This week's scoring data: schedule, recent form and matchups. */
    public LockInScorer.Week current() {
        return current;
    }

    public boolean projectionsAvailable() {
        return !perGameProjections.isEmpty();
    }

    public Double perGameProjection(PlayerSummary player) {
        return perGameProjections.get(player.playerId());
    }

    /** The player's team's games in a week (all of them, played or not). */
    public List<ScheduledGame> gamesOf(PlayerSummary player, int week) {
        if (week == currentWeek) {
            return current.gamesOf(player);
        }
        if (schedule == null || player.team() == null) {
            return List.of();
        }
        return schedule.getOrDefault(week, Map.of()).getOrDefault(player.team(), List.of());
    }

    /** Each remaining week from now through the last fantasy week. */
    public List<Week> weeks(PlayerSummary player) {
        return cache.computeIfAbsent(player.playerId(), id -> {
            List<Week> weeks = new ArrayList<>();
            for (int week = currentWeek; week <= lastWeek; week++) {
                weeks.add(week(player, week));
            }
            return weeks;
        });
    }

    public Week week(PlayerSummary player, int week) {
        if (week < currentWeek || week > lastWeek) {
            return new Week(week, 0, LockInModel.Outcome.NO_GAMES, 0, 0, 0);
        }
        List<Week> cached = cache.get(player.playerId());
        if (cached != null) {
            return cached.get(week - currentWeek);
        }
        return week == currentWeek ? thisWeek(player) : laterWeek(player, week);
    }

    /** For team projections: the week's Lock-In value and its variance. */
    public WeekProjection project(PlayerSummary player, int week) {
        LockInModel.Outcome outcome = week(player, week).outcome();
        return new WeekProjection(outcome.value(), outcome.variance());
    }

    private Week thisWeek(PlayerSummary player) {
        LockInScorer.Analysis analysis = scorer.analyze(player, current);
        List<Double> projected = analysis.games().stream()
                .map(g -> g.projectedPoints() != null ? g.projectedPoints() : 0.0).toList();
        double ratioSum = 0;
        int ratioCount = 0;
        for (LockInScorer.GameOutlook game : analysis.games()) {
            Double ratio = ratio(game.matchup());
            if (ratio != null) {
                ratioSum += ratio;
                ratioCount++;
            }
        }
        return new Week(currentWeek, analysis.games().size(), analysis.outcome(),
                LockInModel.weeklyValue(projected), ratioSum, ratioCount);
    }

    private Week laterWeek(PlayerSummary player, int week) {
        if (player.team() == null) {
            return new Week(week, 0, LockInModel.Outcome.NO_GAMES, 0, 0, 0);
        }
        boolean longTerm = player.injuryStatus() != null && LONG_TERM_ABSENCES.contains(player.injuryStatus());
        PlayerWeek next = week == currentWeek + 1 ? nextWeekProjections.get(player.playerId()) : null;
        Double perGame = perGameProjections.get(player.playerId());
        Double form = longTerm ? null : recentAverage(player);

        // Next week's projections list the games the player is expected to play
        List<Double> projections = new ArrayList<>();
        List<String> opponents = new ArrayList<>();
        if (next != null) {
            next.games().forEach(g -> {
                projections.add(g.projectedPoints());
                opponents.add(g.opponent());
            });
        } else if (!longTerm) {
            for (ScheduledGame game : gamesOf(player, week)) {
                projections.add(perGame);
                opponents.add(game.opponent());
            }
        }

        List<Double> means = new ArrayList<>();
        List<Double> projectionOnly = new ArrayList<>();
        double ratioSum = 0;
        int ratioCount = 0;
        for (int i = 0; i < projections.size(); i++) {
            Double ratio = ratio(opponents.get(i), player.position());
            if (ratio != null) {
                ratioSum += ratio;
                ratioCount++;
            }
            Double proj = projections.get(i);
            Double adjustedForm = form != null
                    ? form * (ratio == null ? 1 : Math.clamp(ratio, 1 - MAX_SCHEDULE_ADJUSTMENT,
                            1 + MAX_SCHEDULE_ADJUSTMENT))
                    : null;
            // if/else, not a ternary: mixing double and Double there would unbox a null
            Double mean;
            if (proj != null && adjustedForm != null) {
                mean = PROJECTION_WEIGHT * proj + FORM_WEIGHT * adjustedForm;
            } else if (proj != null) {
                mean = proj;
            } else {
                mean = adjustedForm;
            }
            if (mean != null) {
                means.add(mean);
                projectionOnly.add(proj != null ? proj : 0);
            }
        }
        return new Week(week, means.size(), LockInModel.evaluate(means), LockInModel.weeklyValue(projectionOnly),
                ratioSum, ratioCount);
    }

    private Double recentAverage(PlayerSummary player) {
        List<RecentGame> recent = current.recentGames().getOrDefault(player.playerId(), List.of());
        return recent.isEmpty() ? null : recent.stream().mapToDouble(RecentGame::points).average().orElse(0);
    }

    private Double ratio(String opponent, String position) {
        return current.defenses().standing(opponent, position)
                .filter(s -> s.leagueAverage() > 0)
                .map(s -> s.allowedPerGame() / s.leagueAverage())
                .orElse(null);
    }

    private static Double ratio(Matchup matchup) {
        if (matchup == null || matchup.allowedPerGame() == null || matchup.leagueAverage() == null
                || matchup.leagueAverage() <= 0) {
            return null;
        }
        return matchup.allowedPerGame() / matchup.leagueAverage();
    }

    /**
     * One game still to play this week or next: {@code projected} is the expected score
     * this week and the projection next week. Rank 1 = the opponent allows the most
     * points to the player's position.
     */
    public record UpcomingGame(int week, String date, String opponent, boolean home, Double projected, Integer rank,
            Integer teams) {
    }

    /** The games left this week and next week's games, for the player detail view. */
    public List<UpcomingGame> upcomingGames(PlayerSummary player) {
        List<UpcomingGame> games = new ArrayList<>();
        for (LockInScorer.GameOutlook game : scorer.analyze(player, current).games()) {
            Matchup m = game.matchup();
            games.add(new UpcomingGame(currentWeek, game.date(), game.opponent(), game.home(), game.expectedPoints(),
                    m != null ? m.rank() : null, m != null ? m.teams() : null));
        }
        if (currentWeek >= lastWeek) {
            return games;
        }
        PlayerWeek next = nextWeekProjections.get(player.playerId());
        Map<String, Double> projectedById = new HashMap<>();
        if (next != null) {
            next.games().forEach(g -> projectedById.put(g.gameId(), g.projectedPoints()));
        }
        for (ScheduledGame game : gamesOf(player, currentWeek + 1)) {
            Double projected = next != null ? projectedById.get(game.gameId())
                    : perGameProjections.get(player.playerId());
            var standing = current.defenses().standing(game.opponent(), player.position());
            games.add(new UpcomingGame(currentWeek + 1, game.date() != null ? game.date().toString() : null,
                    game.opponent(), game.home(), projected != null ? ScoringCalculator.round(projected) : null,
                    standing.map(s -> s.rank()).orElse(null), standing.map(s -> s.teams()).orElse(null)));
        }
        return games;
    }

    /** Rest-of-season Lock-In points: the sum of the remaining weekly values. */
    public double restOfSeason(PlayerSummary player) {
        return weeks(player).stream().mapToDouble(w -> w.outcome().value()).sum();
    }

    /** Rest-of-season value above a replacement-level player at the same position, as a trade value. */
    public TradePlayer evaluate(PlayerSummary player) {
        List<Week> weeks = weeks(player);
        int games = weeks.stream().mapToInt(Week::games).sum();
        double restOfSeason = weeks.stream().mapToDouble(w -> w.outcome().value()).sum();
        Double projected = projectionsAvailable()
                ? ScoringCalculator.round(weeks.stream().mapToDouble(Week::projectionValue).sum())
                : null;
        double ratioSum = weeks.stream().mapToDouble(Week::scheduleRatioSum).sum();
        int ratioCount = weeks.stream().mapToInt(Week::scheduleRatioCount).sum();
        Double schedulePercent = ratioCount == 0 ? null : ScoringCalculator.round((ratioSum / ratioCount - 1) * 100);

        List<RecentGame> recent = current.recentGames().getOrDefault(player.playerId(), List.of());
        Double recentAverage = recent.isEmpty() ? null
                : ScoringCalculator.round(recent.stream().mapToDouble(RecentGame::points).average().orElse(0));
        String note = player.injuryStatus() != null && LONG_TERM_ABSENCES.contains(player.injuryStatus())
                ? player.injuryStatus() : null;
        double replacementPoints = replacement.level(player.position());
        double value;
        if (!replacement.isStartable(player.position())) {
            value = 0;
            note = "No starting slot for " + (player.position() != null ? player.position() : "this position");
        } else {
            value = Math.max(0, restOfSeason - replacementPoints);
        }
        return new TradePlayer(player, games, projected, recentAverage, recent.size(), schedulePercent,
                ScoringCalculator.round(restOfSeason), ScoringCalculator.round(replacementPoints),
                ScoringCalculator.round(value), note);
    }
}
