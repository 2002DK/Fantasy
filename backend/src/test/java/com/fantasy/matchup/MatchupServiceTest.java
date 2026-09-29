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

        MatchupResponse response = new MatchupService(leagueData, calendar, scorer, sleeperClient, lineupService)
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

    private static LeagueData.Team team(int rosterId, String ownerId, PlayerSummary... players) {
        List<String> ids = java.util.Arrays.stream(players).map(PlayerSummary::playerId).toList();
        SleeperRoster roster = new SleeperRoster(rosterId, ownerId, null, ids, ids, null, null, null);
        return new LeagueData.Team(roster, new RosterResponse.Owner(ownerId, ownerId, null, null), List.of(players));
    }

    private static PlayerSummary p(String id, String position, String team) {
        return new PlayerSummary(id, id, position, team, null);
    }
}
