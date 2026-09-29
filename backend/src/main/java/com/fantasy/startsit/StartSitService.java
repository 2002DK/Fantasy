package com.fantasy.startsit;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.fantasy.league.InvalidRequestException;
import com.fantasy.league.NotFoundException;
import com.fantasy.league.SeasonCalendar;
import com.fantasy.player.PlayerService;
import com.fantasy.player.PlayerSummary;
import com.fantasy.sleeper.SleeperClient;
import com.fantasy.sleeper.SleeperLeague;
import com.fantasy.startsit.StartSitResponse.Confidence;
import com.fantasy.startsit.StartSitResponse.Matchup;
import com.fantasy.startsit.StartSitResponse.PlayerAnalysis;
import com.fantasy.startsit.StartSitResponse.Recommendation;
import com.fantasy.stats.Positions;
import com.fantasy.stats.ScoringCalculator;

/**
 * Recommends which of two players to start in a given week, using {@link WeeklyScorer}
 * for each player's expected points and explaining the difference in plain English.
 */
@Service
public class StartSitService {

    static final double TOSS_UP_MARGIN = 0.05;
    static final double LEAN_MARGIN = 0.15;

    private final SleeperClient sleeperClient;
    private final PlayerService playerService;
    private final WeeklyScorer scorer;
    private final SeasonCalendar calendar;

    public StartSitService(SleeperClient sleeperClient, PlayerService playerService, WeeklyScorer scorer,
            SeasonCalendar calendar) {
        this.sleeperClient = sleeperClient;
        this.playerService = playerService;
        this.scorer = scorer;
        this.calendar = calendar;
    }

    public StartSitResponse compare(String leagueId, String playerAId, String playerBId, Integer requestedWeek) {
        if (playerAId.equals(playerBId)) {
            throw new InvalidRequestException("Pick two different players to compare");
        }
        SleeperLeague league = sleeperClient.getLeague(leagueId)
                .orElseThrow(() -> new NotFoundException("No Sleeper league found with ID '" + leagueId + "'"));
        int week = requestedWeek != null ? requestedWeek : currentWeekOrAsk(league);
        PlayerSummary playerA = findPlayer(playerAId);
        PlayerSummary playerB = findPlayer(playerBId);

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

    /** A past-season league has no current week, so the caller must choose one. */
    private int currentWeekOrAsk(SleeperLeague league) {
        try {
            return calendar.currentWeek(league);
        } catch (InvalidRequestException e) {
            throw new InvalidRequestException(
                    "This league is from the " + league.season() + " season; choose a week to compare");
        }
    }

    private PlayerSummary findPlayer(String playerId) {
        return playerService.findSummary(playerId)
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
