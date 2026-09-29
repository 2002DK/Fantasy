package com.fantasy.matchup;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.fantasy.league.LeagueData;
import com.fantasy.league.SeasonCalendar;
import com.fantasy.lineup.LineupResponse;
import com.fantasy.lineup.LineupService;
import com.fantasy.lineup.LineupSolver;
import com.fantasy.matchup.MatchupResponse.Side;
import com.fantasy.matchup.MatchupResponse.Starter;
import com.fantasy.player.PlayerSummary;
import com.fantasy.sleeper.SleeperClient;
import com.fantasy.sleeper.SleeperMatchup;
import com.fantasy.startsit.StartSitResponse.PlayerAnalysis;
import com.fantasy.startsit.WeeklyScorer;
import com.fantasy.stats.ScoreDistribution;
import com.fantasy.stats.ScoringCalculator;

/**
 * Previews this week's head-to-head. Each starter contributes their actual points
 * once their game is final, at least their live points while it is in progress, and
 * their expected points before it starts; uncertainty shrinks as games finish.
 */
@Service
public class MatchupService {

    /** Share of a player's usual uncertainty left while their game is in progress. */
    static final double LIVE_VARIANCE_SHARE = 0.25;

    private final LeagueData leagueData;
    private final SeasonCalendar calendar;
    private final WeeklyScorer scorer;
    private final SleeperClient sleeperClient;
    private final LineupService lineupService;

    public MatchupService(LeagueData leagueData, SeasonCalendar calendar, WeeklyScorer scorer,
            SleeperClient sleeperClient, LineupService lineupService) {
        this.leagueData = leagueData;
        this.calendar = calendar;
        this.scorer = scorer;
        this.sleeperClient = sleeperClient;
        this.lineupService = lineupService;
    }

    public MatchupResponse preview(String leagueId, String userId) {
        LeagueData.Snapshot snapshot = leagueData.load(leagueId);
        LeagueData.Team me = snapshot.teamOf(userId);
        int week = calendar.currentWeek(snapshot.league());
        List<SleeperMatchup> matchups = sleeperClient.getMatchups(leagueId, week);
        WeeklyScorer.Week data = scorer.load(snapshot.league(), week);
        List<String> notes = new ArrayList<>(data.notes());

        SleeperMatchup mine = matchups.stream().filter(m -> m.rosterId() == me.rosterId()).findFirst().orElse(null);
        SleeperMatchup theirs = mine == null || mine.matchupId() == null ? null : matchups.stream()
                .filter(m -> m.rosterId() != me.rosterId() && Objects.equals(m.matchupId(), mine.matchupId()))
                .findFirst().orElse(null);

        List<String> slots = LineupSolver.startingSlots(snapshot.rosterPositions());
        double[] myVariance = new double[1];
        Side you = side(me, mine, slots, snapshot, data, myVariance);
        if (theirs == null) {
            notes.add(0, "You don't have an opponent in week %d.".formatted(week));
            return new MatchupResponse(week, you, null, null, null, null, notes);
        }
        double[] theirVariance = new double[1];
        Side opponent = side(snapshot.team(theirs.rosterId()), theirs, slots, snapshot, data, theirVariance);
        double winProbability = ScoreDistribution.winProbability(
                you.projectedPoints(), myVariance[0], opponent.projectedPoints(), theirVariance[0]);

        // The optimizer only changes players whose games haven't started, so its gain adds to the projection
        LineupResponse optimal = lineupService.optimize(leagueId, userId);
        Double optimizedPoints = null;
        Double optimizedWinProbability = null;
        if (optimal.gain() > 0) {
            optimizedPoints = ScoringCalculator.round(you.projectedPoints() + optimal.gain());
            optimizedWinProbability = ScoringCalculator.round(100 * ScoreDistribution.winProbability(
                    optimizedPoints, myVariance[0], opponent.projectedPoints(), theirVariance[0]));
        }

        if (snapshot.league().settings() != null && snapshot.league().settings().playsMedian()) {
            notes.add("This league also plays the weekly league median, which isn't included in this win probability.");
        }
        return new MatchupResponse(week, you, opponent, ScoringCalculator.round(winProbability * 100), optimizedPoints,
                optimizedWinProbability, notes);
    }

    /** Builds one side; writes the side's score variance into {@code variance[0]}. */
    private Side side(LeagueData.Team team, SleeperMatchup matchup, List<String> slots, LeagueData.Snapshot snapshot,
            WeeklyScorer.Week data, double[] variance) {
        List<String> starterIds = matchup != null && matchup.starters() != null ? matchup.starters()
                : team.roster().starters() != null ? team.roster().starters() : List.of();
        Map<String, Double> livePoints = matchup != null && matchup.playersPoints() != null ? matchup.playersPoints()
                : Map.of();

        List<Starter> starters = new ArrayList<>();
        double actualTotal = 0;
        double projectedTotal = 0;
        for (int i = 0; i < slots.size(); i++) {
            String id = i < starterIds.size() ? starterIds.get(i) : null;
            PlayerSummary player = id == null ? null
                    : team.players().stream().filter(p -> p.playerId().equals(id)).findFirst().orElse(null);
            if (player == null) {
                starters.add(new Starter(slots.get(i), null, 0, 0, 0, "empty", null));
                continue;
            }
            PlayerAnalysis analysis = scorer.analyze(player, data);
            double expected = analysis.score() != null ? analysis.score() : 0;
            double actual = livePoints.getOrDefault(id, 0.0);
            WeeklyScorer.Opponent opponent = data.opponentOf(player);
            String status = gameStatus(opponent);
            double projected = switch (status) {
                case "final" -> actual;
                case "live" -> Math.max(actual, expected);
                default -> expected;
            };
            variance[0] += switch (status) {
                case "final" -> 0;
                case "live" -> LIVE_VARIANCE_SHARE * ScoreDistribution.playerVariance(expected);
                default -> ScoreDistribution.playerVariance(expected);
            };
            actualTotal += actual;
            projectedTotal += projected;
            String opponentLabel = opponent == null ? null : (opponent.home() ? "vs " : "@ ") + opponent.team();
            starters.add(new Starter(slots.get(i), player, ScoringCalculator.round(expected),
                    ScoringCalculator.round(actual), ScoringCalculator.round(projected), status, opponentLabel));
        }
        return new Side(team.rosterId(), team.name(), team.owner().displayName(), team.owner().avatarUrl(),
                ScoringCalculator.round(actualTotal), ScoringCalculator.round(projectedTotal), starters);
    }

    private static String gameStatus(WeeklyScorer.Opponent opponent) {
        if (opponent == null) {
            return "bye";
        }
        return switch (opponent.gameStatus()) {
            case "complete" -> "final";
            case "in_game" -> "live";
            default -> "upcoming";
        };
    }
}
