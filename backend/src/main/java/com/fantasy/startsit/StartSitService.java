package com.fantasy.startsit;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.fantasy.league.InvalidRequestException;
import com.fantasy.league.NotFoundException;
import com.fantasy.league.SeasonCalendar;
import com.fantasy.lockin.LockInScorer;
import com.fantasy.player.PlayerService;
import com.fantasy.player.PlayerSummary;
import com.fantasy.sleeper.SleeperClient;
import com.fantasy.sleeper.SleeperLeague;
import com.fantasy.sleeper.Sport;
import com.fantasy.startsit.StartSitResponse.Confidence;
import com.fantasy.startsit.StartSitResponse.LockInDetail;
import com.fantasy.startsit.StartSitResponse.Matchup;
import com.fantasy.startsit.StartSitResponse.PlayerAnalysis;
import com.fantasy.startsit.StartSitResponse.Recommendation;
import com.fantasy.stats.Positions;
import com.fantasy.stats.ScoringCalculator;

/**
 * Recommends which of two players to start in a given week, explaining the difference
 * in plain English. Football compares expected points from {@link WeeklyScorer};
 * basketball compares each player's Lock-In value for the rest of the week.
 */
@Service
public class StartSitService {

    static final double TOSS_UP_MARGIN = 0.05;
    static final double LEAN_MARGIN = 0.15;

    private final SleeperClient sleeperClient;
    private final PlayerService playerService;
    private final WeeklyScorer scorer;
    private final SeasonCalendar calendar;
    private final LockInScorer lockInScorer;

    public StartSitService(SleeperClient sleeperClient, PlayerService playerService, WeeklyScorer scorer,
            SeasonCalendar calendar, LockInScorer lockInScorer) {
        this.sleeperClient = sleeperClient;
        this.playerService = playerService;
        this.scorer = scorer;
        this.calendar = calendar;
        this.lockInScorer = lockInScorer;
    }

    public StartSitResponse compare(String leagueId, String playerAId, String playerBId, Integer requestedWeek) {
        if (playerAId.equals(playerBId)) {
            throw new InvalidRequestException("Pick two different players to compare");
        }
        SleeperLeague league = sleeperClient.getLeague(leagueId)
                .orElseThrow(() -> new NotFoundException("No Sleeper league found with ID '" + leagueId + "'"));
        int week = requestedWeek != null ? requestedWeek : currentWeekOrAsk(league);
        PlayerSummary playerA = findPlayer(league, playerAId);
        PlayerSummary playerB = findPlayer(league, playerBId);
        if (league.sportType() == Sport.NBA) {
            return compareBasketball(league, week, playerA, playerB);
        }

        WeeklyScorer.Week data = scorer.load(league, week);
        List<String> notes = new ArrayList<>(data.notes());
        PlayerAnalysis a = scorer.analyze(playerA, data);
        PlayerAnalysis b = scorer.analyze(playerB, data);
        Recommendation recommendation = recommend(a, b);
        for (PlayerSummary player : List.of(playerA, playerB)) {
            if (data.isLocked(player)) {
                notes.add("%s's week %d game has already kicked off, so that lineup spot is locked."
                        .formatted(Optional.ofNullable(player.name()).orElse("Player " + player.playerId()), week));
            }
        }
        return new StartSitResponse(league.season(), week, recommendation, List.of(a, b), reasons(a, b, recommendation),
                notes);
    }

    /** Basketball compares the rest of the week under Lock-In, where one game per starter counts. */
    private StartSitResponse compareBasketball(SleeperLeague league, int week, PlayerSummary playerA,
            PlayerSummary playerB) {
        LockInScorer.Week data = lockInScorer.load(league, week);
        List<String> notes = new ArrayList<>(data.notes());
        LockInScorer.Analysis a = lockInScorer.analyze(playerA, data);
        LockInScorer.Analysis b = lockInScorer.analyze(playerB, data);
        PlayerAnalysis analysisA = toAnalysis(a);
        PlayerAnalysis analysisB = toAnalysis(b);
        Recommendation recommendation = recommend(analysisA, analysisB);
        for (PlayerSummary player : List.of(playerA, playerB)) {
            if (data.isPlaying(player)) {
                notes.add(name(player) + " is playing right now, so that lineup spot is locked until the game ends.");
            }
        }
        return new StartSitResponse(league.season(), week, recommendation, List.of(analysisA, analysisB),
                basketballReasons(a, b, recommendation), notes);
    }

    private static PlayerAnalysis toAnalysis(LockInScorer.Analysis a) {
        List<Double> thresholds = a.outcome().thresholds().stream().map(ScoringCalculator::round).toList();
        return new PlayerAnalysis(a.player(), a.available(), a.availabilityNote(), a.projectedPerGame(),
                a.recentAverage(), a.recentGames(), a.nextMatchup(), ScoringCalculator.round(a.value()),
                new LockInDetail(a.games(), thresholds));
    }

    private static List<String> basketballReasons(LockInScorer.Analysis a, LockInScorer.Analysis b,
            Recommendation recommendation) {
        List<String> reasons = new ArrayList<>();
        for (LockInScorer.Analysis p : List.of(a, b)) {
            if (!p.available()) {
                reasons.add(name(p.player()) + switch (p.availabilityNote()) {
                    case "No games left" -> " has no games left this week.";
                    case "No games this week" -> " has no games this week.";
                    case "Not on an NBA team" -> " is not on an NBA team.";
                    case "IR" -> " is on injured reserve.";
                    case "Out" -> " is out and has no other games this week.";
                    default -> " is listed as " + p.availabilityNote() + ".";
                });
            }
        }
        if (!a.available() || !b.available()) {
            return reasons;
        }
        LockInScorer.Analysis hi = a.value() >= b.value() ? a : b;
        LockInScorer.Analysis lo = hi == a ? b : a;
        reasons.add("%s has %s left to %s's %d. Only one game counts, so the week is worth %s to %s."
                .formatted(name(hi.player()), games(hi.games().size()), name(lo.player()), lo.games().size(),
                        fmt(ScoringCalculator.round(hi.value())), fmt(ScoringCalculator.round(lo.value()))));
        if (a.projectedPerGame() != null && b.projectedPerGame() != null) {
            LockInScorer.Analysis more = a.projectedPerGame() >= b.projectedPerGame() ? a : b;
            LockInScorer.Analysis less = more == a ? b : a;
            reasons.add("%s projects %s points a game to %s's %s in your league's scoring."
                    .formatted(name(more.player()), fmt(more.projectedPerGame()), name(less.player()),
                            fmt(less.projectedPerGame())));
        }
        if (a.recentAverage() != null && b.recentAverage() != null) {
            LockInScorer.Analysis more = a.recentAverage() >= b.recentAverage() ? a : b;
            LockInScorer.Analysis less = more == a ? b : a;
            reasons.add("%s has averaged %s over %s to %s's %s over %s."
                    .formatted(name(more.player()), fmt(more.recentAverage()), games(more.recentGames().size()),
                            name(less.player()), fmt(less.recentAverage()), games(less.recentGames().size())));
        }
        for (LockInScorer.Analysis p : List.of(a, b)) {
            Matchup m = p.nextMatchup();
            if (m != null && m.rank() != null) {
                reasons.add("%s's next game is against %s, who allow %s points to %s (%s per game)."
                        .formatted(name(p.player()), m.opponent(), standingPhrase(m.rank(), m.teams()),
                                Positions.pluralName(p.player().position()), fmt(m.allowedPerGame())));
            }
        }
        for (LockInScorer.Analysis p : List.of(a, b)) {
            if (p.availabilityNote() != null) {
                reasons.add(name(p.player()) + switch (p.availabilityNote()) {
                    case "Out" -> " is out for their next game, so it isn't counted.";
                    case "DTD" -> " is day-to-day, so their next game is discounted.";
                    case "GTD" -> " is a game-time decision, so their next game is discounted.";
                    default -> " is " + p.availabilityNote().toLowerCase() + ", so their next game is discounted.";
                });
            }
        }
        if (recommendation != null && recommendation.confidence() == Confidence.TOSS_UP) {
            reasons.add("The values are within 5%, so either choice is reasonable.");
        }
        return reasons;
    }

    /** A past-season league has no current week, so the caller must choose one. */
    private int currentWeekOrAsk(SleeperLeague league) {
        try {
            return calendar.currentWeek(league);
        } catch (InvalidRequestException e) {
            throw new InvalidRequestException(
                    "This league is from the " + league.season() + " season; choose a week to compare");
        }
    }

    private PlayerSummary findPlayer(SleeperLeague league, String playerId) {
        return playerService.findSummary(league.sportType(), playerId)
                .orElseThrow(() -> new NotFoundException("No player found with ID '" + playerId + "'"));
    }

    /** Kept for tests and callers of the start/sit model. */
    static double matchupMultiplier(Matchup matchup) {
        return WeeklyScorer.matchupMultiplier(matchup);
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
        return name(p.player());
    }

    private static String name(PlayerSummary player) {
        return Optional.ofNullable(player.name()).orElse("Player " + player.playerId());
    }

    private static String fmt(double points) {
        return ScoringCalculator.oneDecimal(points);
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
