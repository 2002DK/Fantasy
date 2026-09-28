package com.fantasy.startsit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import com.fantasy.league.InvalidRequestException;
import com.fantasy.league.NotFoundException;
import com.fantasy.player.PlayerService;
import com.fantasy.player.PlayerSummary;
import com.fantasy.sleeper.SleeperClient;
import com.fantasy.sleeper.SleeperGame;
import com.fantasy.sleeper.SleeperLeague;
import com.fantasy.sleeper.SleeperWeeklyEntry;
import com.fantasy.stats.DefenseTable;
import com.fantasy.stats.Positions;
import com.fantasy.stats.ScoringCalculator;
import com.fantasy.stats.WeeklyDataService;
import com.fantasy.startsit.StartSitResponse.Confidence;
import com.fantasy.startsit.StartSitResponse.Matchup;
import com.fantasy.startsit.StartSitResponse.PlayerAnalysis;
import com.fantasy.startsit.StartSitResponse.RecentGame;
import com.fantasy.startsit.StartSitResponse.Recommendation;

/**
 * Recommends which of two players to start in a given week.
 *
 * <p>Score = 60% projected points + 40% recent form, with recent form scaled by
 * matchup (up to ±15%). Projections already account for the opponent, so matchup
 * only adjusts form, which does not. Unavailable players score 0; Questionable and
 * Doubtful players are discounted. All points use the league's own scoring.
 */
@Service
public class StartSitService {

    static final double PROJECTION_WEIGHT = 0.6;
    static final double FORM_WEIGHT = 0.4;
    static final double MAX_MATCHUP_ADJUSTMENT = 0.15;
    static final int FORM_GAMES = 3;
    static final double TOSS_UP_MARGIN = 0.05;
    static final double LEAN_MARGIN = 0.15;
    static final int LAST_REGULAR_SEASON_WEEK = 18;
    /** Below this many completed weeks, points-allowed rankings are mostly noise. */
    static final int RELIABLE_MATCHUP_WEEKS = 4;

    private static final Set<String> UNAVAILABLE_STATUSES = Set.of("Out", "IR", "PUP", "Sus", "NA", "COV", "DNR");
    private static final Map<String, Double> INJURY_DISCOUNTS = Map.of("Questionable", 0.9, "Doubtful", 0.5);

    private static final Logger log = LoggerFactory.getLogger(StartSitService.class);

    private record Opponent(String team, boolean home, String gameStatus) {

        boolean kickedOff() {
            return !"pre_game".equals(gameStatus);
        }
    }

    private final SleeperClient sleeperClient;
    private final WeeklyDataService weeklyData;
    private final PlayerService playerService;

    public StartSitService(SleeperClient sleeperClient, WeeklyDataService weeklyData, PlayerService playerService) {
        this.sleeperClient = sleeperClient;
        this.weeklyData = weeklyData;
        this.playerService = playerService;
    }

    public StartSitResponse compare(String leagueId, String playerAId, String playerBId, Integer requestedWeek) {
        if (playerAId.equals(playerBId)) {
            throw new InvalidRequestException("Pick two different players to compare");
        }
        SleeperLeague league = sleeperClient.getLeague(leagueId)
                .orElseThrow(() -> new NotFoundException("No Sleeper league found with ID '" + leagueId + "'"));
        String season = league.season();
        int week = resolveWeek(season, requestedWeek);
        Map<String, Double> scoring = league.scoringSettings() != null ? league.scoringSettings() : Map.of();
        PlayerSummary playerA = findPlayer(playerAId);
        PlayerSummary playerB = findPlayer(playerBId);

        List<String> notes = new ArrayList<>();
        Map<String, Opponent> opponents = loadOpponents(season, week, notes);
        Map<String, SleeperWeeklyEntry> projections = loadProjections(season, week, notes);
        List<List<SleeperWeeklyEntry>> pastWeeks = loadPastWeeks(season, week, notes);
        DefenseTable defenses = DefenseTable.from(pastWeeks);
        if (!pastWeeks.isEmpty() && pastWeeks.size() < RELIABLE_MATCHUP_WEEKS) {
            notes.add("Matchup rankings use only %s of games so far, so treat them as rough."
                    .formatted(pastWeeks.size() == 1 ? "1 week" : pastWeeks.size() + " weeks"));
        }

        PlayerAnalysis a = analyze(playerA, scoring, opponents, projections, pastWeeks, defenses);
        PlayerAnalysis b = analyze(playerB, scoring, opponents, projections, pastWeeks, defenses);
        Recommendation recommendation = recommend(a, b);
        for (PlayerSummary player : List.of(playerA, playerB)) {
            Opponent opponent = opponents != null && player.team() != null ? opponents.get(player.team()) : null;
            if (opponent != null && opponent.kickedOff()) {
                notes.add("%s's week %d game has already kicked off, so that lineup spot is locked."
                        .formatted(Optional.ofNullable(player.name()).orElse("Player " + player.playerId()), week));
            }
        }

        return new StartSitResponse(season, week, recommendation, List.of(a, b), reasons(a, b, recommendation), notes);
    }

    private int resolveWeek(String season, Integer requestedWeek) {
        if (requestedWeek != null) {
            return requestedWeek;
        }
        var state = sleeperClient.getNflState();
        if (!season.equals(state.season())) {
            throw new InvalidRequestException("This league is from the " + season + " season; choose a week to compare");
        }
        return Math.clamp(state.week(), 1, LAST_REGULAR_SEASON_WEEK);
    }

    private PlayerSummary findPlayer(String playerId) {
        return playerService.findSummary(playerId)
                .orElseThrow(() -> new NotFoundException("No player found with ID '" + playerId + "'"));
    }

    // --- Data loading: each source may fail independently; the comparison uses what it gets ---

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
            Map<String, SleeperWeeklyEntry> byPlayer = new HashMap<>();
            weeklyData.projections(season, week).forEach(entry -> byPlayer.put(entry.playerId(), entry));
            return byPlayer;
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

    // --- Per-player analysis ---

    private PlayerAnalysis analyze(PlayerSummary player, Map<String, Double> scoring, Map<String, Opponent> opponents,
            Map<String, SleeperWeeklyEntry> projections, List<List<SleeperWeeklyEntry>> pastWeeks,
            DefenseTable defenses) {
        SleeperWeeklyEntry projection = projections.get(player.playerId());
        Double projected = projection != null ? ScoringCalculator.points(projection, scoring) : null;

        List<RecentGame> recentGames = recentGames(player.playerId(), pastWeeks, scoring);
        Double recentAverage = recentGames.isEmpty() ? null
                : ScoringCalculator.round(recentGames.stream().mapToDouble(RecentGame::points).average().orElse(0));

        Opponent opponent = opponents != null && player.team() != null ? opponents.get(player.team()) : null;
        Matchup matchup = opponent == null ? null : matchup(opponent, player.position(), defenses);

        String unavailableReason = unavailableReason(player, opponents, opponent);
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

    private static List<RecentGame> recentGames(String playerId, List<List<SleeperWeeklyEntry>> pastWeeks,
            Map<String, Double> scoring) {
        List<RecentGame> games = new ArrayList<>();
        for (List<SleeperWeeklyEntry> week : pastWeeks) {
            week.stream()
                    .filter(e -> playerId.equals(e.playerId()) && e.played())
                    .findFirst()
                    .ifPresent(e -> games.add(new RecentGame(e.week(), e.opponent(), ScoringCalculator.points(e, scoring))));
            if (games.size() == FORM_GAMES) {
                break;
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

    // --- Decision and explanation ---

    private static Recommendation recommend(PlayerAnalysis a, PlayerAnalysis b) {
        if (!a.available() && !b.available()) {
            return null;
        }
        if (a.available() != b.available()) {
            PlayerAnalysis pick = a.available() ? a : b;
            return new Recommendation(pick.player().playerId(), Confidence.CLEAR, 100.0);
        }
        if (a.score() == null || b.score() == null) {
            return null;
        }
        PlayerAnalysis pick = a.score() >= b.score() ? a : b;
        PlayerAnalysis other = pick == a ? b : a;
        double margin = pick.score() > 0 ? (pick.score() - other.score()) / pick.score() : 0;
        Confidence confidence = margin < TOSS_UP_MARGIN ? Confidence.TOSS_UP
                : margin < LEAN_MARGIN ? Confidence.LEAN : Confidence.CLEAR;
        return new Recommendation(pick.player().playerId(), confidence, ScoringCalculator.round(margin * 100));
    }

    private static List<String> reasons(PlayerAnalysis a, PlayerAnalysis b, Recommendation recommendation) {
        List<String> reasons = new ArrayList<>();
        for (PlayerAnalysis p : List.of(a, b)) {
            if (!p.available()) {
                reasons.add(name(p) + (p.availabilityNote().equals("On bye") ? " is on bye this week."
                        : p.availabilityNote().equals("Not on an NFL team") ? " is not on an NFL team."
                        : " is listed as " + p.availabilityNote() + "."));
            }
        }
        if (!a.available() || !b.available()) {
            return reasons;
        }
        if (a.projectedPoints() != null && b.projectedPoints() != null) {
            PlayerAnalysis hi = a.projectedPoints() >= b.projectedPoints() ? a : b;
            PlayerAnalysis lo = hi == a ? b : a;
            reasons.add("%s projects %s points to %s's %s in your league's scoring."
                    .formatted(name(hi), fmt(hi.projectedPoints()), name(lo), fmt(lo.projectedPoints())));
        }
        if (a.recentAverage() != null && b.recentAverage() != null) {
            PlayerAnalysis hi = a.recentAverage() >= b.recentAverage() ? a : b;
            PlayerAnalysis lo = hi == a ? b : a;
            reasons.add("%s has averaged %s over %s to %s's %s over %s."
                    .formatted(name(hi), fmt(hi.recentAverage()), games(hi.recentGames().size()),
                            name(lo), fmt(lo.recentAverage()), games(lo.recentGames().size())));
        }
        if (hasRank(a) && hasRank(b)) {
            for (PlayerAnalysis p : List.of(a, b)) {
                Matchup m = p.matchup();
                reasons.add("%s faces %s, who allow %s points to %s (%s per game)."
                        .formatted(name(p), m.opponent(), standingPhrase(m.rank(), m.teams()),
                                Positions.pluralName(p.player().position()),
                                fmt(m.allowedPerGame())));
            }
        }
        for (PlayerAnalysis p : List.of(a, b)) {
            if (p.availabilityNote() != null) {
                reasons.add(name(p) + " is " + p.availabilityNote().toLowerCase() + ", so their score is discounted.");
            }
        }
        if (recommendation != null && recommendation.confidence() == Confidence.TOSS_UP) {
            reasons.add("The scores are within 5%, so either choice is reasonable.");
        }
        return reasons;
    }


    /** "the most", "the 4th-most", "the 3rd-fewest", "the fewest": whichever end of the table is nearer. */
    static String standingPhrase(int rank, int teams) {
        if (rank == 1) {
            return "the most";
        }
        if (rank == teams) {
            return "the fewest";
        }
        return rank <= (teams + 1) / 2 ? "the " + ordinal(rank) + "-most"
                : "the " + ordinal(teams - rank + 1) + "-fewest";
    }

    private static boolean hasRank(PlayerAnalysis p) {
        return p.matchup() != null && p.matchup().rank() != null;
    }

    private static String name(PlayerAnalysis p) {
        return Optional.ofNullable(p.player().name()).orElse("Player " + p.player().playerId());
    }

    private static String fmt(double points) {
        return "%.1f".formatted(points);
    }

    private static String games(int count) {
        return count == 1 ? "1 game" : count + " games";
    }

    static String ordinal(int n) {
        int mod100 = n % 100;
        String suffix = mod100 >= 11 && mod100 <= 13 ? "th" : switch (n % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
        return n + suffix;
    }
}
