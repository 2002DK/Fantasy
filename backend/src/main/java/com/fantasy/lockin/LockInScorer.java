package com.fantasy.lockin;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import com.fantasy.lockin.LockInService.PlayerWeek;
import com.fantasy.player.PlayerService;
import com.fantasy.player.PlayerSummary;
import com.fantasy.sleeper.SleeperGame;
import com.fantasy.sleeper.SleeperLeague;
import com.fantasy.sleeper.SleeperWeeklyEntry;
import com.fantasy.sleeper.Sport;
import com.fantasy.startsit.StartSitResponse.Matchup;
import com.fantasy.startsit.StartSitResponse.RecentGame;
import com.fantasy.startsit.WeeklyScorer;
import com.fantasy.stats.DefenseTable;
import com.fantasy.stats.ScoringCalculator;
import com.fantasy.stats.WeeklyDataService;

/**
 * Expected Lock-In value of basketball players for one week, in a league's scoring.
 *
 * <p>Each remaining game's expected score blends 60% projection with 40% recent
 * per-game form, the form scaled by up to ±15% for how many points the opponent
 * allows to the player's position (the football model, applied per game). The week's
 * value is then the best lock-or-pass strategy over those games ({@link LockInModel}).
 *
 * <p>When a player has projections, only projected games count: a scheduled game
 * without one means the player is expected to miss it. Players without any projection
 * use form on their team's schedule. Out skips the next game; day-to-day,
 * game-time-decision and questionable players lose 10% of their next game.
 */
@Component
public class LockInScorer {

    static final double PROJECTION_WEIGHT = 0.6;
    static final double FORM_WEIGHT = 0.4;
    static final int FORM_GAMES = 5;
    /** Box-score weeks kept for form and matchups: about 14 games per team, and a bounded download. */
    public static final int PAST_WEEKS = 4;
    /** Below this many completed weeks, points-allowed rankings are mostly noise. */
    static final int RELIABLE_MATCHUP_WEEKS = 2;

    private static final Set<String> UNAVAILABLE_STATUSES = Set.of("IR", "Sus", "NA");
    private static final Map<String, Double> NEXT_GAME_DISCOUNTS =
            Map.of("DTD", 0.9, "GTD", 0.9, "Questionable", 0.9, "Doubtful", 0.5);

    private static final Logger log = LoggerFactory.getLogger(LockInScorer.class);

    /** A team's game this week. Status is pre_game, in_game or complete. */
    public record ScheduledGame(String gameId, LocalDate date, String opponent, boolean home, String status) {

        public boolean complete() {
            return "complete".equals(status);
        }

        public boolean inProgress() {
            return "in_game".equals(status);
        }
    }

    /** Everything needed to score players for one week; {@code gamesByTeam} is null without a schedule. */
    public record Week(
            String season,
            int week,
            Map<String, Double> scoring,
            Map<String, List<ScheduledGame>> gamesByTeam,
            Map<String, PlayerWeek> projections,
            /** Played games, newest first, up to {@link #FORM_GAMES} per player. */
            Map<String, List<RecentGame>> recentGames,
            DefenseTable defenses,
            List<String> notes) {

        public List<ScheduledGame> gamesOf(PlayerSummary player) {
            if (gamesByTeam == null || player.team() == null) {
                return List.of();
            }
            return gamesByTeam.getOrDefault(player.team(), List.of());
        }

        /** Whether the player's team is playing right now, which locks their lineup spot. */
        public boolean isPlaying(PlayerSummary player) {
            return gamesOf(player).stream().anyMatch(ScheduledGame::inProgress);
        }

        /** Games the player's team has finished this week. */
        public int gamesPlayed(PlayerSummary player) {
            return (int) gamesOf(player).stream().filter(ScheduledGame::complete).count();
        }
    }

    /** One remaining game and what the player is expected to score in it. */
    public record GameOutlook(String gameId, String date, String opponent, boolean home, Double projectedPoints,
            double expectedPoints, Matchup matchup) {
    }

    /**
     * A player's outlook for the rest of the week. {@code outcome} covers the remaining
     * games only; {@code value} is its expected counted score (0 when unavailable).
     */
    public record Analysis(
            PlayerSummary player,
            boolean available,
            /** Why the player can't play or carries risk, e.g. "IR", "Out", "DTD", "No games left". */
            String availabilityNote,
            List<GameOutlook> games,
            Double projectedPerGame,
            Double recentAverage,
            List<RecentGame> recentGames,
            LockInModel.Outcome outcome) {

        public double value() {
            return outcome.value();
        }

        public Matchup nextMatchup() {
            return games.isEmpty() ? null : games.getFirst().matchup();
        }
    }

    private final WeeklyDataService weeklyData;
    private final LockInService lockInService;
    private final PlayerService playerService;

    public LockInScorer(WeeklyDataService weeklyData, LockInService lockInService, PlayerService playerService) {
        this.weeklyData = weeklyData;
        this.lockInService = lockInService;
        this.playerService = playerService;
    }

    /** Loads the week's schedule, per-game projections and recent box scores; each may fail on its own. */
    public Week load(SleeperLeague league, int week) {
        String season = league.season();
        Map<String, Double> scoring = league.scoringSettings() != null ? league.scoringSettings() : Map.of();
        List<String> notes = new ArrayList<>();
        Map<String, List<ScheduledGame>> games = loadSchedule(season, week, notes);
        Map<String, PlayerWeek> projections = loadProjections(league, week, notes);
        List<List<SleeperWeeklyEntry>> pastWeeks = loadPastWeeks(season, week, notes);

        List<SleeperWeeklyEntry> boxScores = pastWeeks.stream().flatMap(List::stream)
                .filter(SleeperWeeklyEntry::played).toList();
        Map<String, String> positions = new HashMap<>();
        Set<String> ids = new HashSet<>();
        boxScores.forEach(e -> ids.add(e.playerId()));
        playerService.findSummaries(Sport.NBA, ids).forEach((id, p) -> {
            if (p.position() != null) {
                positions.put(id, p.position());
            }
        });
        if (!pastWeeks.isEmpty() && pastWeeks.size() < RELIABLE_MATCHUP_WEEKS) {
            notes.add("Matchup rankings use only 1 week of games so far, so treat them as rough.");
        }
        return new Week(season, week, scoring, games, projections, recentGames(boxScores, scoring),
                DefenseTable.fromBoxScores(boxScores, positions, scoring), notes);
    }

    public Analysis analyze(PlayerSummary player, Week week) {
        List<RecentGame> recent = week.recentGames().getOrDefault(player.playerId(), List.of());
        Double recentAverage = recent.isEmpty() ? null
                : ScoringCalculator.round(recent.stream().mapToDouble(RecentGame::points).average().orElse(0));
        String status = player.injuryStatus();
        if (status != null && UNAVAILABLE_STATUSES.contains(status)) {
            return unavailable(player, status, recentAverage, recent);
        }
        if (player.team() == null) {
            return unavailable(player, "Not on an NBA team", recentAverage, recent);
        }

        PlayerWeek projected = week.projections().get(player.playerId());
        Map<String, LockInService.Game> projectedById = new HashMap<>();
        if (projected != null) {
            projected.games().forEach(g -> projectedById.put(g.gameId(), g));
        }
        List<GameOutlook> games = new ArrayList<>();
        for (Candidate game : remainingGames(player, week, projected)) {
            LockInService.Game projection = projectedById.get(game.gameId());
            if (projected != null && projection == null) {
                continue; // projected to miss this game
            }
            Matchup matchup = matchup(game, player.position(), week.defenses());
            Double form = recentAverage != null ? recentAverage * WeeklyScorer.matchupMultiplier(matchup) : null;
            Double proj = projection != null ? projection.projectedPoints() : null;
            Double expected = blend(proj, form);
            if (expected != null) {
                games.add(new GameOutlook(game.gameId(), game.date() != null ? game.date().toString() : null,
                        game.opponent(), game.home(), proj, ScoringCalculator.round(expected), matchup));
            }
        }

        String note = null;
        if ("Out".equals(status) && !games.isEmpty()) {
            games.removeFirst();
            note = status;
        } else if (status != null && NEXT_GAME_DISCOUNTS.containsKey(status) && !games.isEmpty()) {
            GameOutlook next = games.getFirst();
            games.set(0, new GameOutlook(next.gameId(), next.date(), next.opponent(), next.home(),
                    next.projectedPoints(),
                    ScoringCalculator.round(next.expectedPoints() * NEXT_GAME_DISCOUNTS.get(status)), next.matchup()));
            note = status;
        } else if ("Out".equals(status)) {
            note = status;
        }
        if (games.isEmpty() && note == null) {
            note = week.gamesOf(player).isEmpty() && week.gamesByTeam() != null ? "No games this week"
                    : "No games left";
        }

        LockInModel.Outcome outcome = LockInModel.evaluate(games.stream().map(GameOutlook::expectedPoints).toList());
        OptionalDouble perGame = games.stream().filter(g -> g.projectedPoints() != null)
                .mapToDouble(GameOutlook::projectedPoints).average();
        Double projectedPerGame = perGame.isPresent() ? ScoringCalculator.round(perGame.getAsDouble()) : null;
        return new Analysis(player, !games.isEmpty(), note, List.copyOf(games), projectedPerGame, recentAverage,
                recent, outcome);
    }

    private static Analysis unavailable(PlayerSummary player, String note, Double recentAverage,
            List<RecentGame> recent) {
        return new Analysis(player, false, note, List.of(), null, recentAverage, recent, LockInModel.Outcome.NO_GAMES);
    }

    private record Candidate(String gameId, LocalDate date, String opponent, boolean home) {
    }

    /** Games not yet finished, from the schedule, or from projections when the schedule is unavailable. */
    private static List<Candidate> remainingGames(PlayerSummary player, Week week, PlayerWeek projected) {
        if (week.gamesByTeam() != null) {
            return week.gamesOf(player).stream().filter(g -> !g.complete())
                    .map(g -> new Candidate(g.gameId(), g.date(), g.opponent(), g.home())).toList();
        }
        if (projected == null) {
            return List.of();
        }
        return projected.games().stream()
                .map(g -> new Candidate(g.gameId(), g.date(), g.opponent(), false)).toList();
    }

    private static Matchup matchup(Candidate game, String position, DefenseTable defenses) {
        return defenses.standing(game.opponent(), position)
                .map(s -> new Matchup(game.opponent(), game.home(), s.rank(), s.teams(),
                        ScoringCalculator.round(s.allowedPerGame()), ScoringCalculator.round(s.leagueAverage())))
                .orElse(new Matchup(game.opponent(), game.home(), null, null, null, null));
    }

    private static Double blend(Double projected, Double form) {
        if (projected != null && form != null) {
            return PROJECTION_WEIGHT * projected + FORM_WEIGHT * form;
        }
        return projected != null ? projected : form;
    }

    // --- Loading: each source may fail independently ---

    private Map<String, List<ScheduledGame>> loadSchedule(String season, int week, List<String> notes) {
        try {
            Map<String, List<ScheduledGame>> byTeam = new HashMap<>();
            for (SleeperGame game : weeklyData.schedule(Sport.NBA, season)) {
                if (game.week() != week || game.isCanceled() || game.home() == null || game.away() == null) {
                    continue;
                }
                LocalDate date = parseDate(game.date());
                byTeam.computeIfAbsent(game.home(), t -> new ArrayList<>())
                        .add(new ScheduledGame(game.gameId(), date, game.away(), true, game.status()));
                byTeam.computeIfAbsent(game.away(), t -> new ArrayList<>())
                        .add(new ScheduledGame(game.gameId(), date, game.home(), false, game.status()));
            }
            Comparator<ScheduledGame> byDate = Comparator.comparing(ScheduledGame::date,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            byTeam.values().forEach(list -> list.sort(byDate));
            return byTeam;
        } catch (RestClientException e) {
            log.warn("Basketball schedule unavailable for {}", season, e);
            notes.add("The NBA schedule is unavailable right now, so games are taken from projections.");
            return null;
        }
    }

    private Map<String, PlayerWeek> loadProjections(SleeperLeague league, int week, List<String> notes) {
        try {
            return lockInService.projectWeek(league, week);
        } catch (RestClientException e) {
            log.warn("Basketball projections unavailable for {} week {}", league.season(), week, e);
            notes.add("Projections are unavailable right now; this uses recent form and matchups only.");
            return Map.of();
        }
    }

    /** Recent completed weeks, newest first. */
    private List<List<SleeperWeeklyEntry>> loadPastWeeks(String season, int week, List<String> notes) {
        if (week == 1) {
            notes.add("No games have been played yet this season, so recent form and matchup data are not available.");
            return List.of();
        }
        try {
            List<List<SleeperWeeklyEntry>> weeks = new ArrayList<>();
            for (int w = week - 1; w >= Math.max(1, week - PAST_WEEKS); w--) {
                weeks.add(weeklyData.stats(Sport.NBA, season, w));
            }
            return weeks;
        } catch (RestClientException e) {
            log.warn("Basketball box scores unavailable for {}", season, e);
            notes.add("Past box scores are unavailable right now; this uses projections only.");
            return List.of();
        }
    }

    private static Map<String, List<RecentGame>> recentGames(List<SleeperWeeklyEntry> boxScores,
            Map<String, Double> scoring) {
        Map<String, List<SleeperWeeklyEntry>> byPlayer = new HashMap<>();
        boxScores.forEach(e -> byPlayer.computeIfAbsent(e.playerId(), id -> new ArrayList<>()).add(e));
        Map<String, List<RecentGame>> recent = new HashMap<>();
        Comparator<SleeperWeeklyEntry> newestFirst = Comparator
                .comparing(SleeperWeeklyEntry::date, Comparator.nullsFirst(Comparator.naturalOrder())).reversed();
        byPlayer.forEach((id, entries) -> recent.put(id, entries.stream().sorted(newestFirst).limit(FORM_GAMES)
                .map(e -> new RecentGame(e.week(), e.opponent(), ScoringCalculator.points(e, scoring)))
                .toList()));
        return recent;
    }

    private static LocalDate parseDate(String date) {
        try {
            return date != null ? LocalDate.parse(date) : null;
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
