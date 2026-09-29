package com.fantasy.startsit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import com.fantasy.player.PlayerSummary;
import com.fantasy.sleeper.SleeperGame;
import com.fantasy.sleeper.SleeperLeague;
import com.fantasy.sleeper.SleeperWeeklyEntry;
import com.fantasy.startsit.StartSitResponse.Matchup;
import com.fantasy.startsit.StartSitResponse.PlayerAnalysis;
import com.fantasy.startsit.StartSitResponse.RecentGame;
import com.fantasy.stats.DefenseTable;
import com.fantasy.stats.ScoringCalculator;
import com.fantasy.stats.WeeklyDataService;

/**
 * Expected fantasy points for players in one week, in a league's scoring.
 *
 * <p>Score = 60% projected points + 40% recent form, with recent form scaled by
 * matchup (up to ±15%). Projections already account for the opponent, so matchup
 * only adjusts form, which does not. Unavailable players score 0; Questionable and
 * Doubtful players are discounted.
 *
 * <p>{@link #load} fetches the week's data once; {@link #analyze} then scores any
 * number of players against it, which the start/sit comparison, lineup optimizer
 * and matchup preview all do.
 */
@Component
public class WeeklyScorer {

    static final double PROJECTION_WEIGHT = 0.6;
    static final double FORM_WEIGHT = 0.4;
    static final double MAX_MATCHUP_ADJUSTMENT = 0.15;
    static final int FORM_GAMES = 3;
    /** Below this many completed weeks, points-allowed rankings are mostly noise. */
    static final int RELIABLE_MATCHUP_WEEKS = 4;

    private static final Set<String> UNAVAILABLE_STATUSES = Set.of("Out", "IR", "PUP", "Sus", "NA", "COV", "DNR");
    private static final Map<String, Double> INJURY_DISCOUNTS = Map.of("Questionable", 0.9, "Doubtful", 0.5);

    private static final Logger log = LoggerFactory.getLogger(WeeklyScorer.class);

    /** A team's game this week. Status is pre_game, in_game or complete. */
    public record Opponent(String team, boolean home, String gameStatus) {

        public boolean kickedOff() {
            return !"pre_game".equals(gameStatus);
        }
    }

    /**
     * Everything needed to score players for one week. {@code opponents} is null when
     * the schedule could not be loaded (byes and matchups are then not checked).
     */
    public record Week(
            String season,
            int week,
            Map<String, Double> scoring,
            Map<String, Opponent> opponents,
            Map<String, SleeperWeeklyEntry> projections,
            /** Completed weeks, newest first, each keyed by player ID. */
            List<Map<String, SleeperWeeklyEntry>> pastWeeksByPlayer,
            DefenseTable defenses,
            List<String> notes) {

        public Opponent opponentOf(PlayerSummary player) {
            return opponents != null && player.team() != null ? opponents.get(player.team()) : null;
        }

        /** Whether the player's game this week has started, which locks their lineup spot. */
        public boolean isLocked(PlayerSummary player) {
            Opponent opponent = opponentOf(player);
            return opponent != null && opponent.kickedOff();
        }
    }

    private final WeeklyDataService weeklyData;

    public WeeklyScorer(WeeklyDataService weeklyData) {
        this.weeklyData = weeklyData;
    }

    /** Loads the week's schedule, projections and past stats. Each source may fail independently. */
    public Week load(SleeperLeague league, int week) {
        String season = league.season();
        Map<String, Double> scoring = league.scoringSettings() != null ? league.scoringSettings() : Map.of();
        List<String> notes = new ArrayList<>();
        Map<String, Opponent> opponents = loadOpponents(season, week, notes);
        Map<String, SleeperWeeklyEntry> projections = loadProjections(season, week, notes);
        List<List<SleeperWeeklyEntry>> pastWeeks = loadPastWeeks(season, week, notes);
        if (!pastWeeks.isEmpty() && pastWeeks.size() < RELIABLE_MATCHUP_WEEKS) {
            notes.add("Matchup rankings use only %s of games so far, so treat them as rough."
                    .formatted(pastWeeks.size() == 1 ? "1 week" : pastWeeks.size() + " weeks"));
        }
        List<Map<String, SleeperWeeklyEntry>> byPlayer = pastWeeks.stream().map(WeeklyScorer::indexByPlayer).toList();
        return new Week(season, week, scoring, opponents, projections, byPlayer, DefenseTable.from(pastWeeks), notes);
    }

    private static Map<String, SleeperWeeklyEntry> indexByPlayer(List<SleeperWeeklyEntry> entries) {
        Map<String, SleeperWeeklyEntry> index = new HashMap<>();
        entries.forEach(e -> index.put(e.playerId(), e));
        return index;
    }

    private Map<String, Opponent> loadOpponents(String season, int week, List<String> notes) {
        try {
            Map<String, Opponent> opponents = new HashMap<>();
            for (SleeperGame game : weeklyData.schedule(season)) {
                if (game.week() == week && !game.isCanceled()) {
                    opponents.put(game.home(), new Opponent(game.away(), true, game.status()));
                    opponents.put(game.away(), new Opponent(game.home(), false, game.status()));
                }
            }
            return opponents;
        } catch (RestClientException e) {
            log.warn("Schedule unavailable for {}", season, e);
            notes.add("The NFL schedule is unavailable right now, so byes and matchups are not checked.");
            return null;
        }
    }

    private Map<String, SleeperWeeklyEntry> loadProjections(String season, int week, List<String> notes) {
        try {
            return indexByPlayer(weeklyData.projections(season, week));
        } catch (RestClientException e) {
            log.warn("Projections unavailable for {} week {}", season, week, e);
            notes.add("Projections are unavailable right now; this uses recent form and matchup only.");
            return Map.of();
        }
    }

    /** Completed weeks, newest first. */
    private List<List<SleeperWeeklyEntry>> loadPastWeeks(String season, int week, List<String> notes) {
        if (week == 1) {
            notes.add("No games have been played yet this season, so recent form and matchup data are not available.");
            return List.of();
        }
        try {
            List<List<SleeperWeeklyEntry>> weeks = new ArrayList<>();
            for (int w = week - 1; w >= 1; w--) {
                weeks.add(weeklyData.stats(season, w));
            }
            return weeks;
        } catch (RestClientException e) {
            log.warn("Weekly stats unavailable for {}", season, e);
            notes.add("Past game stats are unavailable right now; this uses projections only.");
            return List.of();
        }
    }

    public PlayerAnalysis analyze(PlayerSummary player, Week week) {
        SleeperWeeklyEntry projection = week.projections().get(player.playerId());
        Double projected = projection != null ? ScoringCalculator.points(projection, week.scoring()) : null;

        List<RecentGame> recentGames = recentGames(player.playerId(), week);
        Double recentAverage = recentGames.isEmpty() ? null
                : ScoringCalculator.round(recentGames.stream().mapToDouble(RecentGame::points).average().orElse(0));

        Opponent opponent = week.opponentOf(player);
        Matchup matchup = opponent == null ? null : matchup(opponent, player.position(), week.defenses());

        String unavailableReason = unavailableReason(player, week.opponents(), opponent);
        if (unavailableReason != null) {
            return new PlayerAnalysis(player, false, unavailableReason, projected, recentAverage, recentGames,
                    matchup, 0.0);
        }
        Double score = score(projected, recentAverage, matchup);
        // Map.of rejects null lookups, and healthy players have a null injury status
        Double discount = player.injuryStatus() != null ? INJURY_DISCOUNTS.get(player.injuryStatus()) : null;
        if (score != null && discount != null) {
            score *= discount;
        }
        return new PlayerAnalysis(player, true, discount != null ? player.injuryStatus() : null,
                projected, recentAverage, recentGames, matchup, score != null ? ScoringCalculator.round(score) : null);
    }

    private static String unavailableReason(PlayerSummary player, Map<String, Opponent> opponents, Opponent opponent) {
        if (player.injuryStatus() != null && UNAVAILABLE_STATUSES.contains(player.injuryStatus())) {
            return player.injuryStatus();
        }
        if (player.team() == null) {
            return "Not on an NFL team";
        }
        if (opponents != null && opponent == null) {
            return "On bye";
        }
        return null;
    }

    private static List<RecentGame> recentGames(String playerId, Week week) {
        List<RecentGame> games = new ArrayList<>();
        for (Map<String, SleeperWeeklyEntry> pastWeek : week.pastWeeksByPlayer()) {
            SleeperWeeklyEntry entry = pastWeek.get(playerId);
            if (entry != null && entry.played()) {
                games.add(new RecentGame(entry.week(), entry.opponent(), ScoringCalculator.points(entry, week.scoring())));
                if (games.size() == FORM_GAMES) {
                    break;
                }
            }
        }
        return games;
    }

    private static Matchup matchup(Opponent opponent, String position, DefenseTable defenses) {
        return defenses.standing(opponent.team(), position)
                .map(s -> new Matchup(opponent.team(), opponent.home(), s.rank(), s.teams(),
                        ScoringCalculator.round(s.allowedPerGame()), ScoringCalculator.round(s.leagueAverage())))
                .orElse(new Matchup(opponent.team(), opponent.home(), null, null, null, null));
    }

    /** Scales form by how the opponent's points allowed compare to the league average, capped at ±15%. */
    static double matchupMultiplier(Matchup matchup) {
        if (matchup == null || matchup.allowedPerGame() == null || matchup.leagueAverage() == null
                || matchup.leagueAverage() <= 0) {
            return 1.0;
        }
        double ratio = matchup.allowedPerGame() / matchup.leagueAverage();
        return Math.clamp(ratio, 1 - MAX_MATCHUP_ADJUSTMENT, 1 + MAX_MATCHUP_ADJUSTMENT);
    }

    private static Double score(Double projected, Double recentAverage, Matchup matchup) {
        Double adjustedForm = recentAverage != null ? recentAverage * matchupMultiplier(matchup) : null;
        if (projected != null && adjustedForm != null) {
            return PROJECTION_WEIGHT * projected + FORM_WEIGHT * adjustedForm;
        }
        return projected != null ? projected : adjustedForm;
    }
}
