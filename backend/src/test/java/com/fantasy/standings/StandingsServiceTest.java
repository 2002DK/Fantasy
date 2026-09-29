package com.fantasy.standings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fantasy.league.LeagueData;
import com.fantasy.league.RosterResponse;
import com.fantasy.league.SeasonCalendar;
import com.fantasy.player.PlayerSummary;
import com.fantasy.sleeper.SleeperClient;
import com.fantasy.sleeper.SleeperLeague;
import com.fantasy.sleeper.SleeperMatchup;
import com.fantasy.sleeper.SleeperRoster;
import com.fantasy.sleeper.SleeperWeeklyEntry;
import com.fantasy.standings.StandingsResponse.TeamStanding;
import com.fantasy.stats.DefenseTable;
import com.fantasy.trade.SeasonValuer;

/**
 * Four one-QB teams, all 6-6, in week 13 of a league whose playoffs (2 teams) start in
 * week 15. Team 1's QB projects 30 a week, the others 10-12, so team 1 is the
 * strongest and a near-lock for the playoffs.
 */
@ExtendWith(MockitoExtension.class)
class StandingsServiceTest {

    private static final Map<Integer, Double> WEEKLY = Map.of(1, 30.0, 2, 10.0, 3, 12.0, 4, 11.0);

    @Mock
    private LeagueData leagueData;
    @Mock
    private SeasonCalendar calendar;
    @Mock
    private SeasonValuer valuer;
    @Mock
    private SleeperClient sleeperClient;

    @Test
    void ranksByProjectedStrengthAndSimulatesPlayoffOdds() {
        SleeperLeague league = new SleeperLeague("L1", "Dynasty", "2026", "in_season", 4, null,
                List.of("QB", "BN"), Map.of("pts", 1.0), new SleeperLeague.Settings(15, 2, 0, 0));
        List<LeagueData.Team> teams = new ArrayList<>();
        for (int id = 1; id <= 4; id++) {
            SleeperRoster roster = new SleeperRoster(id, id == 1 ? "me" : "u" + id, null, List.of("qb" + id), List.of(),
                    null, null, new SleeperRoster.Settings(6, 6, 0, 600 + id, 0, 600, 0));
            teams.add(new LeagueData.Team(roster, new RosterResponse.Owner("u" + id, "Owner " + id, "Team " + id, null),
                    List.of(new PlayerSummary("qb" + id, "QB " + id, "QB", "PHI", null))));
        }
        given(leagueData.load("L1")).willReturn(new LeagueData.Snapshot(league, teams));
        given(calendar.currentWeek(league)).willReturn(13);
        given(valuer.load(any(), anyInt(), anyInt())).willReturn(season());
        given(sleeperClient.getMatchups("L1", 13)).willReturn(List.of(m(1, 1), m(2, 1), m(3, 2), m(4, 2)));
        given(sleeperClient.getMatchups("L1", 14)).willReturn(List.of(m(1, 1), m(3, 1), m(2, 2), m(4, 2)));

        StandingsResponse response = new StandingsService(leagueData, calendar, valuer, sleeperClient,
                Clock.systemUTC()).standings("L1", "me");

        assertThat(response.regularSeasonEnd()).isEqualTo(14);
        assertThat(response.simulations()).isEqualTo(StandingsService.SIMULATIONS);
        TeamStanding strongest = response.teams().stream().filter(TeamStanding::you).findFirst().orElseThrow();
        assertThat(strongest.rosterId()).isEqualTo(1);
        assertThat(strongest.strength()).isEqualTo(30.0);
        assertThat(strongest.powerRank()).isEqualTo(1);
        assertThat(strongest.playoffOdds()).isGreaterThan(90.0);
        assertThat(response.teams().stream().mapToDouble(TeamStanding::playoffOdds).sum()).isCloseTo(200, within(0.1));
        // Standings order is wins, then points for: all 6-6, so team 4 (604 points) leads
        assertThat(response.teams().getFirst().rosterId()).isEqualTo(4);
    }

    @Test
    void teamsSharingAMatchupIdArePaired() {
        List<int[]> pairs = StandingsService.pairs(List.of(m(1, 7), m(2, 3), m(3, 7), m(4, 3), m(5, null)));

        assertThat(pairs).hasSize(2);
        assertThat(pairs).anySatisfy(p -> assertThat(p).containsExactlyInAnyOrder(1, 3));
        assertThat(pairs).anySatisfy(p -> assertThat(p).containsExactlyInAnyOrder(2, 4));
    }

    private static SleeperMatchup m(int rosterId, Integer matchupId) {
        return new SleeperMatchup(rosterId, matchupId, 0, List.of(), Map.of());
    }

    private static SeasonValuer.Season season() {
        Map<Integer, Map<String, SleeperWeeklyEntry>> projections = new HashMap<>();
        for (int week = 13; week <= 15; week++) {
            Map<String, SleeperWeeklyEntry> byPlayer = new HashMap<>();
            for (var e : WEEKLY.entrySet()) {
                String id = "qb" + e.getKey();
                byPlayer.put(id, new SleeperWeeklyEntry(id, "PHI", "DAL", week, Map.of("pts", e.getValue(), "gp", 1)));
            }
            projections.put(week, byPlayer);
        }
        return new SeasonValuer.Season(Map.of("pts", 1.0), 13, 15, null, projections, true, List.of(),
                DefenseTable.from(List.of()), null, new ArrayList<>());
    }
}
