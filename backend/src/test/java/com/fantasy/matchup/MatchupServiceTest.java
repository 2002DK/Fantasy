package com.fantasy.matchup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fantasy.league.LeagueData;
import com.fantasy.league.RosterResponse;
import com.fantasy.league.SeasonCalendar;
import com.fantasy.lineup.LineupResponse;
import com.fantasy.lineup.LineupService;
import com.fantasy.lockin.LockInModel;
import com.fantasy.lockin.LockInScorer;
import com.fantasy.player.PlayerSummary;
import com.fantasy.sleeper.SleeperClient;
import com.fantasy.sleeper.SleeperLeague;
import com.fantasy.sleeper.SleeperMatchup;
import com.fantasy.sleeper.SleeperRoster;
import com.fantasy.startsit.StartSitResponse.PlayerAnalysis;
import com.fantasy.startsit.WeeklyScorer;
import com.fantasy.stats.DefenseTable;
import com.fantasy.stats.ScoreDistribution;

/**
 * One QB and one RB slot. Your QB's game is final (25 actual, expected 20 before it);
 * your RB (expected 10) hasn't played. Their QB (expected 22) and RB (expected 12) are
 * both upcoming. Projected: you 25 + 10 = 35, them 34.
 */
@ExtendWith(MockitoExtension.class)
class MatchupServiceTest {

    private static final PlayerSummary MY_QB = p("q1", "QB", "PHI");
    private static final PlayerSummary MY_RB = p("r1", "RB", "DAL");
    private static final PlayerSummary THEIR_QB = p("q2", "QB", "KC");
    private static final PlayerSummary THEIR_RB = p("r2", "RB", "BUF");
    private static final Map<String, Double> EXPECTED = Map.of("q1", 20.0, "r1", 10.0, "q2", 22.0, "r2", 12.0);

    @Mock
    private LeagueData leagueData;
    @Mock
    private SeasonCalendar calendar;
    @Mock
    private WeeklyScorer scorer;
    @Mock
    private SleeperClient sleeperClient;
    @Mock
    private LineupService lineupService;

    @Test
    void combinesFinalLiveAndExpectedPointsIntoAWinProbability() {
        SleeperLeague league = new SleeperLeague("L1", "Dynasty", "2026", "in_season", 2, null,
                List.of("QB", "RB", "BN"), Map.of(), new SleeperLeague.Settings(15, 2, 0, 1));
        LeagueData.Team me = team(1, "me", MY_QB, MY_RB);
        LeagueData.Team them = team(2, "them", THEIR_QB, THEIR_RB);
        given(leagueData.load("L1")).willReturn(new LeagueData.Snapshot(league, List.of(me, them)));
        given(calendar.currentWeek(league)).willReturn(5);
        given(sleeperClient.getMatchups("L1", 5)).willReturn(List.of(
                new SleeperMatchup(1, 3, 25, List.of("q1", "r1"), Map.of("q1", 25.0, "r1", 0.0)),
                new SleeperMatchup(2, 3, 0, List.of("q2", "r2"), Map.of("q2", 0.0, "r2", 0.0))));
        given(scorer.load(any(), anyInt())).willReturn(new WeeklyScorer.Week("2026", 5, Map.of(), Map.of(
                "PHI", new WeeklyScorer.Opponent("NYG", true, "complete"),
                "DAL", new WeeklyScorer.Opponent("WAS", false, "pre_game"),
                "KC", new WeeklyScorer.Opponent("LV", true, "pre_game"),
                "BUF", new WeeklyScorer.Opponent("MIA", false, "pre_game")),
                Map.of(), List.of(), DefenseTable.from(List.of()), new ArrayList<>()));
        given(scorer.analyze(any(), any())).willAnswer(inv -> {
            PlayerSummary player = inv.getArgument(0);
            return new PlayerAnalysis(player, true, null, null, null, List.of(), null, EXPECTED.get(player.playerId()));
        });
        given(lineupService.optimize("L1", "me")).willReturn(
                new LineupResponse(5, List.of(), 30, 34, 4, List.of(), List.of()));

        MatchupResponse response = new MatchupService(leagueData, calendar, scorer, sleeperClient, lineupService, null)
                .preview("L1", "me");

        assertThat(response.you().actualPoints()).isEqualTo(25.0);
        assertThat(response.you().projectedPoints()).isEqualTo(35.0);
        assertThat(response.you().starters().getFirst().status()).isEqualTo("final");
        assertThat(response.opponent().projectedPoints()).isEqualTo(34.0);
        // Remaining variance: your RB (5^2) vs their QB and RB (11^2 + 6^2)
        double expected = 100 * ScoreDistribution.winProbability(35, 25, 34, 121 + 36);
        assertThat(response.winProbability()).isCloseTo(expected, within(0.01));
        assertThat(response.optimizedPoints()).isEqualTo(39.0);
        assertThat(response.optimizedWinProbability()).isGreaterThan(response.winProbability());
        assertThat(response.notes()).anyMatch(n -> n.contains("league median"));
    }

    @Mock
    private LockInScorer lockInScorer;

    /**
     * PG and C slots under Lock-In. Your PG scored 50 with games left worth 40, so the
     * 50 is worth keeping; your C is out of games with 30. Their PG hasn't scored (45
     * left to play); their C scored 20 with games left worth 30, so they'd pass.
     */
    @Test
    void basketballCountsOneGamePerStarter() {
        SleeperLeague league = new SleeperLeague("L2", "Hoops", "2026", "in_season", 2, null,
                List.of("PG", "C", "BN"), Map.of(), null, "nba");
        LeagueData.Team me = team(1, "me", p("a", "PG", "DAL"), p("b", "C", "BOS"));
        LeagueData.Team them = team(2, "them", p("c", "PG", "MIA"), p("d", "C", "NYK"));
        given(leagueData.load("L2")).willReturn(new LeagueData.Snapshot(league, List.of(me, them)));
        given(calendar.currentWeek(league)).willReturn(3);
        given(sleeperClient.getMatchups("L2", 3)).willReturn(List.of(
                new SleeperMatchup(1, 1, 80, List.of("a", "b"), Map.of("a", 50.0, "b", 30.0)),
                new SleeperMatchup(2, 1, 20, List.of("c", "d"), Map.of("c", 0.0, "d", 20.0))));
        LockInScorer.ScheduledGame played = new LockInScorer.ScheduledGame("g0", null, "LAL", true, "complete");
        given(lockInScorer.load(any(), anyInt())).willReturn(new LockInScorer.Week("2026", 3, Map.of(),
                Map.of("DAL", List.of(played), "BOS", List.of(played), "MIA", List.of(played), "NYK", List.of(played)),
                Map.of(), Map.of(), DefenseTable.from(List.of()), new ArrayList<>()));
        Map<String, double[]> outlook = Map.of( // games left, value, variance
                "a", new double[] {2, 40, 81}, "b", new double[] {0, 0, 0},
                "c", new double[] {3, 45, 100}, "d", new double[] {1, 30, 64});
        given(lockInScorer.analyze(any(), any())).willAnswer(inv -> {
            PlayerSummary player = inv.getArgument(0);
            double[] o = outlook.get(player.playerId());
            List<LockInScorer.GameOutlook> games = java.util.Collections.nCopies((int) o[0],
                    new LockInScorer.GameOutlook("g", "2026-11-04", "LAL", false, null, o[1], null));
            return new LockInScorer.Analysis(player, o[0] > 0, o[0] > 0 ? null : "No games left", games, null, null,
                    List.of(), new LockInModel.Outcome(o[1], o[2], List.of()));
        });
        given(lineupService.optimize("L2", "me")).willReturn(
                new LineupResponse(3, List.of(), 80, 80, 0, List.of(), List.of()));

        MatchupResponse response = new MatchupService(leagueData, calendar, null, sleeperClient, lineupService,
                lockInScorer).preview("L2", "me");

        assertThat(response.you().projectedPoints()).isEqualTo(80.0);
        assertThat(response.you().starters()).extracting(MatchupResponse.Starter::status)
                .containsExactly("upcoming", "final");
        assertThat(response.you().starters()).extracting(MatchupResponse.Starter::gamesLeft).containsExactly(2, 0);
        assertThat(response.you().starters().getFirst().opponent()).isEqualTo("@ LAL");
        assertThat(response.opponent().projectedPoints()).isEqualTo(75.0);
        // Your side is settled; theirs keeps the spread of both open slots
        double expected = 100 * ScoreDistribution.winProbability(80, 0, 75, 100 + 64);
        assertThat(response.winProbability()).isCloseTo(expected, within(0.01));
        assertThat(response.optimizedPoints()).isNull();
        assertThat(response.notes()).anyMatch(n -> n.startsWith("Each starter counts one game"));
    }

    private static LeagueData.Team team(int rosterId, String ownerId, PlayerSummary... players) {
        List<String> ids = java.util.Arrays.stream(players).map(PlayerSummary::playerId).toList();
        SleeperRoster roster = new SleeperRoster(rosterId, ownerId, null, ids, ids, null, null, null);
        return new LeagueData.Team(roster, new RosterResponse.Owner(ownerId, ownerId, null, null), List.of(players));
    }

    private static PlayerSummary p(String id, String position, String team) {
        return new PlayerSummary(id, id, position, team, null);
    }
}
