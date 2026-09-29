package com.fantasy.trade;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import com.fantasy.player.PlayerService;
import com.fantasy.player.PlayerSummary;
import com.fantasy.sleeper.SleeperGame;
import com.fantasy.sleeper.SleeperLeague;
import com.fantasy.sleeper.SleeperWeeklyEntry;
import com.fantasy.stats.DefenseTable;
import com.fantasy.stats.ScoringCalculator;
import com.fantasy.stats.WeeklyDataService;
import com.fantasy.trade.TradeResponse.TradePlayer;

/**
 * Rest-of-season value of players in one league, in its scoring.
 *
 * <p>Rest-of-season points = 70% the sum of weekly projections for the player's
 * remaining games + 30% recent form carried forward over those games, with the form
 * part scaled up to ±15% by remaining schedule strength (projections already reflect
 * each opponent). Value = those points minus a replacement-level player's at the same
 * position, floored at 0.
 *
 * <p>{@link #load} fetches the season's data once; {@link #evaluate} then values any
 * number of players against it, which the trade analyzer, trade finder and power
 * rankings all do.
 */
@Component
public class SeasonValuer {

    static final double PROJECTION_WEIGHT = 0.7;
    static final double FORM_WEIGHT = 0.3;
    static final double MAX_SCHEDULE_ADJUSTMENT = 0.15;
    static final int FORM_GAMES = 3;
    static final int RELIABLE_SCHEDULE_WEEKS = 4;

    /** Statuses that keep a player out for weeks, so recent form should not be carried forward. */
    private static final Set<String> LONG_TERM_ABSENCES = Set.of("IR", "PUP", "Sus", "NA");

    private static final Logger log = LoggerFactory.getLogger(SeasonValuer.class);

    /** Everything needed to value players from {@code currentWeek} through {@code lastWeek}. */
    public record Season(
            Map<String, Double> scoring,
            int currentWeek,
            int lastWeek,
            /** week -> team -> game; null when the schedule could not be loaded. */
            Map<Integer, Map<String, SleeperGame>> gamesByWeekAndTeam,
            /** week -> player -> projection; empty when projections could not be loaded. */
            Map<Integer, Map<String, SleeperWeeklyEntry>> projectionsByWeek,
            boolean projectionsAvailable,
            /** Completed weeks, newest first, each keyed by player ID. */
            List<Map<String, SleeperWeeklyEntry>> pastWeeksByPlayer,
            DefenseTable defenses,
            ReplacementLevels replacement,
            List<String> notes) {

        /** The player's team's game in a week, or null on a bye (or when the schedule is unknown). */
        public SleeperGame gameOf(PlayerSummary player, int week) {
            if (gamesByWeekAndTeam == null || player.team() == null) {
                return null;
            }
            return gamesByWeekAndTeam.getOrDefault(week, Map.of()).get(player.team());
        }
    }

    private final WeeklyDataService weeklyData;
    private final PlayerService playerService;

    public SeasonValuer(WeeklyDataService weeklyData, PlayerService playerService) {
        this.weeklyData = weeklyData;
        this.playerService = playerService;
    }

    public Season load(SleeperLeague league, int currentWeek, int lastWeek) {
        String season = league.season();
        List<String> notes = new ArrayList<>();
        Map<String, Double> scoring = league.scoringSettings() != null ? league.scoringSettings() : Map.of();
        Map<Integer, Map<String, SleeperGame>> games = loadGames(season, currentWeek, lastWeek, notes);
        Map<Integer, Map<String, SleeperWeeklyEntry>> projections = loadProjections(season, currentWeek, lastWeek, notes);
        List<List<SleeperWeeklyEntry>> pastWeeks = loadPastWeeks(season, currentWeek, notes);
        if (!pastWeeks.isEmpty() && pastWeeks.size() < RELIABLE_SCHEDULE_WEEKS) {
            notes.add("Schedule strength uses only %s of games so far, so treat it as rough."
                    .formatted(pastWeeks.size() == 1 ? "1 week" : pastWeeks.size() + " weeks"));
        }
        List<Map<String, SleeperWeeklyEntry>> pastByPlayer = pastWeeks.stream().map(SeasonValuer::indexByPlayer).toList();

        Season partial = new Season(scoring, currentWeek, lastWeek, games,
                projections != null ? projections : Map.of(), projections != null, pastByPlayer,
                DefenseTable.from(pastWeeks), null, notes);
        return new Season(scoring, currentWeek, lastWeek, games, partial.projectionsByWeek(),
                partial.projectionsAvailable(), pastByPlayer, partial.defenses(), replacementLevels(league, partial),
                notes);
    }

    private static Map<String, SleeperWeeklyEntry> indexByPlayer(List<SleeperWeeklyEntry> entries) {
        Map<String, SleeperWeeklyEntry> index = new HashMap<>();
        entries.forEach(e -> index.put(e.playerId(), e));
        return index;
    }

    // --- Data loading: each source may fail independently ---

    private Map<Integer, Map<String, SleeperGame>> loadGames(String season, int from, int to, List<String> notes) {
        try {
            Map<Integer, Map<String, SleeperGame>> byWeek = new HashMap<>();
            for (SleeperGame game : weeklyData.schedule(season)) {
                if (game.week() >= from && game.week() <= to && !game.isCanceled()) {
                    Map<String, SleeperGame> byTeam = byWeek.computeIfAbsent(game.week(), w -> new HashMap<>());
                    byTeam.put(game.home(), game);
                    byTeam.put(game.away(), game);
                }
            }
            return byWeek;
        } catch (RestClientException e) {
            log.warn("Schedule unavailable for {}", season, e);
            notes.add("The NFL schedule is unavailable right now, so byes and schedule strength are not counted.");
            return null;
        }
    }

    private Map<Integer, Map<String, SleeperWeeklyEntry>> loadProjections(String season, int from, int to,
            List<String> notes) {
        try {
            Map<Integer, Map<String, SleeperWeeklyEntry>> byWeek = new HashMap<>();
            weeklyData.projections(season, from, to).forEach((week, entries) -> byWeek.put(week, indexByPlayer(entries)));
            return byWeek;
        } catch (RestClientException e) {
            log.warn("Projections unavailable for {} weeks {}-{}", season, from, to, e);
            notes.add("Projections are unavailable right now, so values use recent form only and positional "
                    + "scarcity is not applied.");
            return null;
        }
    }

    /** Completed weeks, newest first. */
    private List<List<SleeperWeeklyEntry>> loadPastWeeks(String season, int currentWeek, List<String> notes) {
        if (currentWeek == 1) {
            return List.of();
        }
        try {
            List<List<SleeperWeeklyEntry>> weeks = new ArrayList<>();
            for (int w = currentWeek - 1; w >= 1; w--) {
                weeks.add(weeklyData.stats(season, w));
            }
            return weeks;
        } catch (RestClientException e) {
            log.warn("Weekly stats unavailable for {}", season, e);
            notes.add("Past game stats are unavailable right now, so recent form is not counted.");
            return List.of();
        }
    }

    // --- Valuation ---

    /** Rest-of-season projections of every projected player, grouped by position. */
    private ReplacementLevels replacementLevels(SleeperLeague league, Season season) {
        List<String> rosterPositions = league.rosterPositions() != null ? league.rosterPositions() : List.of();
        if (!season.projectionsAvailable()) {
            return ReplacementLevels.compute(rosterPositions, league.totalRosters(), Map.of());
        }
        Set<String> projectedIds = new LinkedHashSet<>();
        season.projectionsByWeek().values().forEach(byPlayer -> projectedIds.addAll(byPlayer.keySet()));
        Map<String, List<Double>> rosByPosition = new HashMap<>();
        playerService.findSummaries(projectedIds).values().forEach(p -> {
            if (p.position() != null) {
                rosByPosition.computeIfAbsent(p.position(), k -> new ArrayList<>())
                        .add(projectedPoints(p, remainingWeeks(p, season), season));
            }
        });
        return ReplacementLevels.compute(rosterPositions, league.totalRosters(), rosByPosition);
    }

    /**
     * Weeks from now through the season's last week in which the player's team plays,
     * skipping byes and this week's game if it has already kicked off.
     */
    public static List<Integer> remainingWeeks(PlayerSummary player, Season season) {
        List<Integer> weeks = new ArrayList<>();
        if (player.team() == null) {
            return weeks;
        }
        for (int week = season.currentWeek(); week <= season.lastWeek(); week++) {
            if (season.gamesByWeekAndTeam() == null) {
                weeks.add(week);
                continue;
            }
            SleeperGame game = season.gameOf(player, week);
            if (game != null && (week > season.currentWeek() || "pre_game".equals(game.status()))) {
                weeks.add(week);
            }
        }
        return weeks;
    }

    /** Projected points in one week in the league's scoring, or null without a projection. */
    public static Double projectedPoints(PlayerSummary player, int week, Season season) {
        SleeperWeeklyEntry entry = season.projectionsByWeek().getOrDefault(week, Map.of()).get(player.playerId());
        return entry != null ? ScoringCalculator.points(entry, season.scoring()) : null;
    }

    private static double projectedPoints(PlayerSummary player, List<Integer> weeks, Season season) {
        double total = 0;
        for (int week : weeks) {
            Double points = projectedPoints(player, week, season);
            if (points != null) {
                total += points;
            }
        }
        return ScoringCalculator.round(total);
    }

    public TradePlayer evaluate(PlayerSummary player, Season season) {
        List<Integer> weeks = remainingWeeks(player, season);
        int games = weeks.size();
        Double projected = season.projectionsAvailable() ? projectedPoints(player, weeks, season) : null;

        List<Double> recent = recentPoints(player.playerId(), season);
        Double recentAverage = recent.isEmpty() ? null
                : ScoringCalculator.round(recent.stream().mapToDouble(Double::doubleValue).average().orElse(0));
        Double scheduleRatio = scheduleRatio(player, weeks, season);
        double scheduleMultiplier = scheduleRatio == null ? 1.0
                : Math.clamp(scheduleRatio, 1 - MAX_SCHEDULE_ADJUSTMENT, 1 + MAX_SCHEDULE_ADJUSTMENT);

        boolean longTermAbsence = player.injuryStatus() != null && LONG_TERM_ABSENCES.contains(player.injuryStatus());
        Double formPoints = recentAverage != null && !longTermAbsence ? recentAverage * games * scheduleMultiplier : null;
        double restOfSeason;
        if (projected != null && formPoints != null) {
            restOfSeason = PROJECTION_WEIGHT * projected + FORM_WEIGHT * formPoints;
        } else if (projected != null) {
            restOfSeason = projected;
        } else {
            restOfSeason = formPoints != null ? formPoints : 0;
        }

        String note = longTermAbsence ? player.injuryStatus() : null;
        double replacementPoints = season.replacement().level(player.position());
        double value;
        if (!season.replacement().isStartable(player.position())) {
            value = 0;
            note = "No starting slot for " + Optional.ofNullable(player.position()).orElse("this position");
        } else {
            value = Math.max(0, restOfSeason - replacementPoints);
        }
        return new TradePlayer(player, games, projected, recentAverage, recent.size(),
                scheduleRatio == null ? null : ScoringCalculator.round((scheduleRatio - 1) * 100),
                ScoringCalculator.round(restOfSeason), ScoringCalculator.round(replacementPoints),
                ScoringCalculator.round(value), note);
    }

    private static List<Double> recentPoints(String playerId, Season season) {
        List<Double> points = new ArrayList<>();
        for (Map<String, SleeperWeeklyEntry> week : season.pastWeeksByPlayer()) {
            SleeperWeeklyEntry entry = week.get(playerId);
            if (entry != null && entry.played()) {
                points.add(ScoringCalculator.points(entry, season.scoring()));
                if (points.size() == FORM_GAMES) {
                    break;
                }
            }
        }
        return points;
    }

    /**
     * Average points the player's remaining opponents allow to their position,
     * relative to the league average (1.10 = 10% easier). Null without data.
     */
    private static Double scheduleRatio(PlayerSummary player, List<Integer> weeks, Season season) {
        if (season.gamesByWeekAndTeam() == null || player.team() == null) {
            return null;
        }
        double ratioSum = 0;
        int counted = 0;
        for (int week : weeks) {
            SleeperGame game = season.gameOf(player, week);
            if (game == null) {
                continue;
            }
            String opponent = player.team().equals(game.home()) ? game.away() : game.home();
            var standing = season.defenses().standing(opponent, player.position());
            if (standing.isPresent() && standing.get().leagueAverage() > 0) {
                ratioSum += standing.get().allowedPerGame() / standing.get().leagueAverage();
                counted++;
            }
        }
        return counted == 0 ? null : ratioSum / counted;
    }
}
