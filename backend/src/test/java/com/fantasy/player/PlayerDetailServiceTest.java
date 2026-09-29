package com.fantasy.player;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fantasy.league.LeagueData;
import com.fantasy.league.RosterResponse;
import com.fantasy.league.SeasonCalendar;
import com.fantasy.sleeper.SleeperGame;
import com.fantasy.sleeper.SleeperLeague;
import com.fantasy.sleeper.SleeperPlayer;
import com.fantasy.sleeper.SleeperRoster;
import com.fantasy.sleeper.SleeperWeeklyEntry;
import com.fantasy.stats.DefenseTable;
import com.fantasy.trade.SeasonValuer;
import com.fantasy.trade.TradeResponse.TradePlayer;

@ExtendWith(MockitoExtension.class)
class PlayerDetailServiceTest {

    private static final PlayerSummary BIJAN = new PlayerSummary("9509", "Bijan Robinson", "RB", "ATL", null);

    @Mock
    private LeagueData leagueData;
    @Mock
    private SeasonCalendar calendar;
    @Mock
    private SeasonValuer valuer;
    @Mock
    private PlayerService playerService;

    @Test
    void showsGamesSoFarUpcomingScheduleAndWhoRostersThePlayer() {
        SleeperLeague league = new SleeperLeague("L1", "Dynasty", "2026", "in_season", 2, null, List.of("RB"),
                Map.of("rush_yd", 0.1), new SleeperLeague.Settings(6, 2, 0));
        SleeperRoster roster = new SleeperRoster(2, "them", null, List.of("9509"), List.of("9509"), null, null, null);
        LeagueData.Team owner = new LeagueData.Team(roster, new RosterResponse.Owner("them", "Sam", "Bijan Fans", null),
                List.of(BIJAN));
        given(playerService.findPlayer("9509")).willReturn(Optional.of(Player.from("9509", new SleeperPlayer(
                "9509", "Bijan Robinson", "Bijan", "Robinson", "RB", List.of("RB"), "ATL", null, true, 24, 3, 5))));
        given(playerService.findSummary("9509")).willReturn(Optional.of(BIJAN));
        given(leagueData.load("L1")).willReturn(new LeagueData.Snapshot(league, List.of(owner)));
        given(calendar.currentWeek(league)).willReturn(5);
        given(valuer.evaluate(any(), any())).willReturn(new TradePlayer(BIJAN, 2, 40.0, 15.0, 2, null, 38, 20, 18, null));

        // Played weeks 3 and 4 (newest first); ATL plays week 5 at NO, bye in week 6
        Map<String, SleeperWeeklyEntry> week4 = Map.of("9509", entry(4, "GB", 150));
        Map<String, SleeperWeeklyEntry> week3 = Map.of("9509", entry(3, "CAR", 90));
        Map<Integer, Map<String, SleeperGame>> games = new HashMap<>();
        games.put(5, Map.of("NO", new SleeperGame("g", 5, "NO", "ATL", "pre_game"),
                "ATL", new SleeperGame("g", 5, "NO", "ATL", "pre_game")));
        Map<Integer, Map<String, SleeperWeeklyEntry>> projections = Map.of(5, Map.of("9509", entry(5, "NO", 200)));
        given(valuer.load(any(), anyInt(), anyInt())).willReturn(new SeasonValuer.Season(Map.of("rush_yd", 0.1), 5, 6,
                games, projections, true, List.of(week4, week3), DefenseTable.from(List.of()), null, new ArrayList<>()));

        PlayerDetailResponse detail = new PlayerDetailService(leagueData, calendar, valuer, playerService)
                .detail("L1", "9509", "me");

        assertThat(detail.age()).isEqualTo(24);
        assertThat(detail.rosteredBy()).isEqualTo("Bijan Fans");
        assertThat(detail.onYourTeam()).isFalse();
        assertThat(detail.games()).extracting(PlayerDetailResponse.GamePlayed::week).containsExactly(3, 4);
        assertThat(detail.games().get(1).points()).isEqualTo(15.0);
        assertThat(detail.upcoming()).hasSize(2);
        assertThat(detail.upcoming().getFirst().opponent()).isEqualTo("@ NO");
        assertThat(detail.upcoming().getFirst().projected()).isEqualTo(20.0);
        assertThat(detail.upcoming().get(1).opponent()).isNull();
    }

    private static SleeperWeeklyEntry entry(int week, String opponent, double rushYards) {
        return new SleeperWeeklyEntry("9509", "ATL", opponent, week, Map.of("gp", 1, "rush_yd", rushYards));
    }
}
