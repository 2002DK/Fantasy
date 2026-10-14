package com.fantasy.matchup;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.fantasy.league.LeagueData;
import com.fantasy.league.SeasonCalendar;
import com.fantasy.lineup.LineupResponse;
import com.fantasy.lineup.LineupService;
import com.fantasy.lineup.LineupSolver;
import com.fantasy.lockin.LockInAdviceResponse.PlayedGame;
import com.fantasy.lockin.LockInMark;
import com.fantasy.lockin.LockInMarkRepository;
import com.fantasy.lockin.LockInScorer;
import com.fantasy.matchup.MatchupResponse.Side;
import com.fantasy.matchup.MatchupResponse.Starter;
import com.fantasy.player.PlayerSummary;
import com.fantasy.sleeper.SleeperClient;
import com.fantasy.sleeper.SleeperMatchup;
import com.fantasy.sleeper.Sport;
import com.fantasy.startsit.StartSitResponse.PlayerAnalysis;
import com.fantasy.startsit.WeeklyScorer;
import com.fantasy.stats.ScoreDistribution;
import com.fantasy.stats.ScoringCalculator;

/**
 * Previews this week's head-to-head. Each starter contributes their actual points
 * once their game is final, at least their live points while it is in progress, and
 * their expected points before it starts; uncertainty shrinks as games finish.
 * Basketball counts one game per starter under Lock-In.
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
    private final LockInScorer lockInScorer;
    private final LockInMarkRepository lockMarks;

    public MatchupService(LeagueData leagueData, SeasonCalendar calendar, WeeklyScorer scorer,
            SleeperClient sleeperClient, LineupService lineupService, LockInScorer lockInScorer,
            LockInMarkRepository lockMarks) {
        this.leagueData = leagueData;
        this.calendar = calendar;
        this.scorer = scorer;
        this.sleeperClient = sleeperClient;
        this.lineupService = lineupService;
        this.lockInScorer = lockInScorer;
        this.lockMarks = lockMarks;
    }

    public MatchupResponse preview(String leagueId, String userId) {
        LeagueData.Snapshot snapshot = leagueData.load(leagueId);
        LeagueData.Team me = snapshot.teamOf(userId);
        int week = calendar.currentWeek(snapshot.league());
        List<SleeperMatchup> matchups = sleeperClient.getMatchups(leagueId, week);
        boolean basketball = snapshot.league().sportType() == Sport.NBA;
        WeeklyScorer.Week data = basketball ? null : scorer.load(snapshot.league(), week);
        LockInScorer.Week lockInData = basketball ? lockInScorer.load(snapshot.league(), week) : null;
        List<String> notes = new ArrayList<>(basketball ? lockInData.notes() : data.notes());

        SleeperMatchup mine = matchups.stream().filter(m -> m.rosterId() == me.rosterId()).findFirst().orElse(null);
        SleeperMatchup theirs = mine == null || mine.matchupId() == null ? null : matchups.stream()
                .filter(m -> m.rosterId() != me.rosterId() && Objects.equals(m.matchupId(), mine.matchupId()))
                .findFirst().orElse(null);

        List<String> slots = LineupSolver.startingSlots(snapshot.rosterPositions());
        double[] myVariance = new double[1];
        Map<String, LockInMark> myLocks = new HashMap<>();
        if (basketball) {
            lockMarks.findByLeagueIdAndUserIdAndWeek(leagueId, userId, week)
                    .forEach(m -> myLocks.put(m.getPlayerId(), m));
        }
        Side you = basketball ? basketballSide(me, mine, slots, lockInData, myLocks, myVariance)
                : side(me, mine, slots, snapshot, data, myVariance);
        if (theirs == null) {
            notes.add(0, "You don't have an opponent in week %d.".formatted(week));
            return new MatchupResponse(week, you, null, null, null, null, notes);
        }
        double[] theirVariance = new double[1];
        LeagueData.Team them = snapshot.team(theirs.rosterId());
        Side opponent = basketball ? basketballSide(them, theirs, slots, lockInData, Map.of(), theirVariance)
                : side(them, theirs, slots, snapshot, data, theirVariance);
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

        if (basketball) {
            notes.add("Each starter counts one game: a locked score, a score they can still lock, or the "
                    + "Lock-In value of their remaining games. Scores you mark as locked in the Lock-In tab are "
                    + "final here, as is any score where Sleeper counts an earlier game than the latest (for both "
                    + "teams); other latest scores count as still lockable.");
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

    /**
     * Basketball: a starter with no games left counts their score as final. Otherwise
     * they count the better of their current score (which they can lock before their
     * next game) and the Lock-In value of the games left; the spread narrows when the
     * current score is worth keeping. A score marked as locked is final. Writes the side's
     * variance into {@code variance[0]}.
     */
    private Side basketballSide(LeagueData.Team team, SleeperMatchup matchup, List<String> slots,
            LockInScorer.Week data, Map<String, LockInMark> locks, double[] variance) {
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
                starters.add(new Starter(slots.get(i), null, 0, 0, 0, "empty", null, 0));
                continue;
            }
            LockInScorer.Analysis analysis = lockInScorer.analyze(player, data);
            double actual = livePoints.getOrDefault(id, 0.0);
            int gamesLeft = analysis.games().size();
            double projected;
            String status;
            LockInMark mark = locks.get(id);
            PlayedGame inferred = mark == null ? data.inferredLock(id, actual) : null;
            if (mark != null || inferred != null) {
                projected = mark != null ? mark.getPoints() : inferred.points();
                status = "locked";
            } else if (gamesLeft == 0) {
                projected = actual;
                status = actual == 0 && data.gamesOf(player).isEmpty() ? "bye" : "final";
            } else {
                boolean keep = actual > 0 && actual >= analysis.value();
                projected = Math.max(actual, analysis.value());
                variance[0] += keep ? 0 : analysis.outcome().variance();
                status = data.isPlaying(player) ? "live" : "upcoming";
            }
            actualTotal += actual;
            projectedTotal += projected;
            LockInScorer.GameOutlook next = analysis.games().isEmpty() ? null : analysis.games().getFirst();
            String opponentLabel = next == null ? null : (next.home() ? "vs " : "@ ") + next.opponent();
            starters.add(new Starter(slots.get(i), player, ScoringCalculator.round(projected),
                    ScoringCalculator.round(actual), ScoringCalculator.round(projected), status, opponentLabel,
                    gamesLeft));
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
