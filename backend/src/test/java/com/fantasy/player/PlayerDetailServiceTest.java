package com.fantasy.player;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
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
import com.fantasy.lockin.BasketballSeason;
import com.fantasy.lockin.BasketballSeasonValuer;
import com.fantasy.lockin.LockInModel;
import com.fantasy.sleeper.SleeperGame;
import com.fantasy.sleeper.SleeperLeague;
import com.fantasy.sleeper.SleeperPlayer;
import com.fantasy.sleeper.SleeperRoster;
import com.fantasy.sleeper.SleeperWeeklyEntry;
import com.fantasy.sleeper.Sport;
import com.fantasy.stats.DefenseTable;
import com.fantasy.stats.WeeklyDataService;
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
        given(playerService.findPlayer(Sport.NFL, "9509")).willReturn(Optional.of(Player.from(Sport.NFL, "9509", new SleeperPlayer(
                "9509", "Bijan Robinson", "Bijan", "Robinson", "RB", List.of("RB"), "ATL", null, true, 24, 3, 5))));
        given(playerService.findSummary(Sport.NFL, "9509")).willReturn(Optional.of(BIJAN));
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

        PlayerDetailResponse detail = new PlayerDetailService(leagueData, calendar, valuer, playerService, null, null)
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

    @Mock
    private BasketballSeasonValuer basketballValuer;
    @Mock
    private BasketballSeason basketballSeason;
    @Mock
    private WeeklyDataService weeklyData;

    @Test
    void basketballShowsRecentGamesUpcomingGamesAndWeeklyLockInValues() {
        PlayerSummary luka = new PlayerSummary("1", "Luka", "PG", "LAL", null);
        SleeperLeague hoops = new SleeperLeague("H1", "Hoops", "2026", "in_season", 2, null, List.of("PG"),
                Map.of("pts", 1.0), null, "nba");
        given(playerService.findPlayer(Sport.NBA, "1")).willReturn(Optional.of(Player.from(Sport.NBA, "1",
                new SleeperPlayer("1", "Luka", "Luka", "Doncic", "PG", List.of("PG"), "LAL", null, true, 27, 8, 1))));
        given(playerService.findSummary(Sport.NBA, "1")).willReturn(Optional.of(luka));
        given(leagueData.load("H1")).willReturn(new LeagueData.Snapshot(hoops, List.of()));
        given(calendar.currentWeek(hoops)).willReturn(3);
        given(basketballValuer.load(any(), anyInt(), anyInt())).willReturn(basketballSeason);
        given(basketballSeason.notes()).willReturn(List.of());
        given(basketballSeason.evaluate(luka)).willReturn(new TradePlayer(luka, 40, 900.0, 50.0, 5, null, 950, 700,
                250, null));
        given(basketballSeason.upcomingGames(luka)).willReturn(List.of(
                new BasketballSeason.UpcomingGame(3, "2026-11-04", "MIA", false, 52.0, 1, 30)));
        given(basketballSeason.weeks(luka)).willReturn(List.of(
                new BasketballSeason.Week(3, 2, new LockInModel.Outcome(58.4, 0, List.of()), 55, 0, 0)));
        // Games from earlier weeks and this week, out of order
        given(weeklyData.stats(eq(Sport.NBA), eq("2026"), anyInt())).willAnswer(inv -> (int) inv.getArgument(2) == 2
                ? List.of(game("2026-10-30", "BOS", 48), game("2026-10-28", "NYK", 61)) : List.of());
        given(weeklyData.liveStats(Sport.NBA, "2026", 3)).willReturn(List.of(game("2026-11-02", "DEN", 40)));

        PlayerDetailResponse detail = new PlayerDetailService(leagueData, calendar, valuer, playerService,
                basketballValuer, weeklyData).detail("H1", "1", "me");

        assertThat(detail.games()).extracting(PlayerDetailResponse.GamePlayed::date)
                .containsExactly("2026-10-28", "2026-10-30", "2026-11-02");
        assertThat(detail.games()).extracting(PlayerDetailResponse.GamePlayed::points).containsExactly(61.0, 48.0, 40.0);
        assertThat(detail.upcoming()).containsExactly(
                new PlayerDetailResponse.Upcoming(3, "@ MIA", 52.0, 1, 30, "2026-11-04"));
        assertThat(detail.lockInWeeks()).containsExactly(new PlayerDetailResponse.WeekValue(3, 2, 58.4));
        assertThat(detail.value().value()).isEqualTo(250.0);
        assertThat(detail.rosteredBy()).isNull();
    }

    private static SleeperWeeklyEntry game(String date, String opponent, double points) {
        return new SleeperWeeklyEntry("1", "LAL", opponent, 2, Map.of("pts", points, "sp", 2000.0), date, date);
    }

    private static SleeperWeeklyEntry entry(int week, String opponent, double rushYards) {
        return new SleeperWeeklyEntry("9509", "ATL", opponent, week, Map.of("gp", 1, "rush_yd", rushYards));
    }
}
