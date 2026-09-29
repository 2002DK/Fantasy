package com.fantasy.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fantasy.league.LeagueData;
import com.fantasy.league.RosterResponse;
import com.fantasy.league.SeasonCalendar;
import com.fantasy.planner.PlannerResponse.Cell;
import com.fantasy.planner.PlannerResponse.Row;
import com.fantasy.player.PlayerSummary;
import com.fantasy.sleeper.SleeperGame;
import com.fantasy.sleeper.SleeperLeague;
import com.fantasy.sleeper.SleeperRoster;
import com.fantasy.stats.DefenseTable;
import com.fantasy.trade.SeasonValuer;

/**
 * Weeks 4-6 (playoffs start week 6 with 2 teams, so week 6 is the last). Two RB slots.
 * Alpha (PHI) is healthy; Beta (KC) is Questionable, KC already played in week 4 and
 * is on bye in week 5; Cee (LV) is on injured reserve. Week 5 leaves only Alpha, so
 * one RB slot can't be filled.
 */
@ExtendWith(MockitoExtension.class)
class PlannerServiceTest {

    private static final PlayerSummary ALPHA = new PlayerSummary("A", "Alpha", "RB", "PHI", null);
    private static final PlayerSummary BETA = new PlayerSummary("B", "Beta", "RB", "KC", "Questionable");
    private static final PlayerSummary CEE = new PlayerSummary("C", "Cee", "RB", "LV", "IR");

    @Mock
    private LeagueData leagueData;
    @Mock
    private SeasonCalendar calendar;
    @Mock
    private SeasonValuer valuer;

    @Test
    void mapsAvailabilityByWeekAndFindsShortages() {
        SleeperLeague league = new SleeperLeague("L1", "Dynasty", "2026", "in_season", 2, null,
                List.of("RB", "RB", "BN", "IR"), Map.of(), new SleeperLeague.Settings(6, 2, 0));
        SleeperRoster roster = new SleeperRoster(1, "me", null, List.of("A", "B", "C"), List.of("A", "B"),
                List.of("C"), null, null);
        LeagueData.Team team = new LeagueData.Team(roster, new RosterResponse.Owner("me", "Dani", null, null),
                List.of(ALPHA, BETA, CEE));
        given(leagueData.load("L1")).willReturn(new LeagueData.Snapshot(league, List.of(team)));
        given(calendar.currentWeek(league)).willReturn(4);
        given(valuer.load(any(), any(Integer.class), any(Integer.class))).willReturn(season(Map.of(
                4, games(game("PHI", "DAL", "pre_game"), game("KC", "LV", "complete")),
                5, games(game("PHI", "NYG", "pre_game")),
                6, games(game("DAL", "PHI", "pre_game"), game("KC", "DEN", "pre_game")))));

        PlannerResponse plan = new PlannerService(leagueData, calendar, valuer).plan("L1", "me");

        assertThat(plan.weeks()).containsExactly(4, 5, 6);
        assertThat(statuses(plan, "A")).containsExactly("ok", "ok", "ok");
        assertThat(statuses(plan, "B")).containsExactly("played", "bye", "ok");
        assertThat(statuses(plan, "C")).containsExactly("out", "bye", "bye");
        assertThat(row(plan, "A").cells().get(2).opponent()).isEqualTo("@ DAL");
        assertThat(row(plan, "A").starter()).isTrue();
        assertThat(plan.shortages()).singleElement().satisfies(s -> {
            assertThat(s.week()).isEqualTo(5);
            assertThat(s.unfilledSlots()).containsExactly("RB");
            assertThat(s.message()).isEqualTo("Week 5: no available player for RB.");
        });
    }

    private static List<String> statuses(PlannerResponse plan, String playerId) {
        return row(plan, playerId).cells().stream().map(Cell::status).toList();
    }

    private static Row row(PlannerResponse plan, String playerId) {
        return plan.players().stream().filter(r -> r.player().playerId().equals(playerId)).findFirst().orElseThrow();
    }

    private static SleeperGame game(String home, String away, String status) {
        return new SleeperGame(home + away, 0, home, away, status);
    }

    private static Map<String, SleeperGame> games(SleeperGame... games) {
        Map<String, SleeperGame> byTeam = new java.util.HashMap<>();
        for (SleeperGame g : games) {
            byTeam.put(g.home(), g);
            byTeam.put(g.away(), g);
        }
        return byTeam;
    }

    private static SeasonValuer.Season season(Map<Integer, Map<String, SleeperGame>> games) {
        return new SeasonValuer.Season(Map.of(), 4, 6, games, Map.of(), false, List.of(), DefenseTable.from(List.of()),
                null, new java.util.ArrayList<>());
    }
}
