package com.fantasy.planner;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.fantasy.lockin.BasketballSeason;
import com.fantasy.lockin.BasketballSeasonValuer;
import com.fantasy.lockin.LockInModel;
import com.fantasy.lockin.LockInScorer;
import com.fantasy.planner.PlannerResponse.Cell;
import com.fantasy.planner.PlannerResponse.Row;
import com.fantasy.player.PlayerSummary;
import com.fantasy.sleeper.SleeperGame;
import com.fantasy.sleeper.SleeperLeague;
import com.fantasy.sleeper.SleeperRoster;
import com.fantasy.stats.DefenseTable;
import com.fantasy.trade.ReplacementLevels;
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

        PlannerResponse plan = new PlannerService(leagueData, calendar, valuer, null).plan("L1", "me");

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

    @Mock
    private BasketballSeasonValuer basketballValuer;
    @Mock
    private LockInScorer lockInScorer;

    /**
     * Basketball weeks 3-5, PG and C slots. PHI (guard) plays 2, 1 and 2 games; BOS
     * (center) plays 2, none and 1. Week 4 can't fill C; week 5's lineup averages 1.5
     * games per starter, a light week.
     */
    @Test
    void basketballShowsGamesPerWeekAndFlagsLightWeeks() {
        SleeperLeague league = new SleeperLeague("H1", "Hoops", "2026", "in_season", 2, null,
                List.of("PG", "C", "BN"), Map.of(), new SleeperLeague.Settings(5, 2, 0), "nba");
        PlayerSummary guard = new PlayerSummary("G", "Guard", "PG", "PHI", null);
        PlayerSummary center = new PlayerSummary("C", "Center", "C", "BOS", null);
        SleeperRoster roster = new SleeperRoster(1, "me", null, List.of("G", "C"), List.of("G", "C"), null, null,
                null);
        LeagueData.Team team = new LeagueData.Team(roster, new RosterResponse.Owner("me", "Dani", null, null),
                List.of(guard, center));
        given(leagueData.load("H1")).willReturn(new LeagueData.Snapshot(league, List.of(team)));
        given(calendar.currentWeek(league)).willReturn(3);
        given(lockInScorer.analyze(any(), any())).willAnswer(inv -> new LockInScorer.Analysis(inv.getArgument(0),
                true, null, List.of(), null, null, List.of(), new LockInModel.Outcome(40, 0, List.of())));
        LockInScorer.Week current = new LockInScorer.Week("2026", 3, Map.of(), Map.of(
                "PHI", List.of(upcoming("PHI1"), upcoming("PHI2")), "BOS", List.of(upcoming("BOS1"), upcoming("BOS2"))),
                Map.of(), Map.of(), DefenseTable.from(List.of()), new ArrayList<>());
        BasketballSeason season = new BasketballSeason(lockInScorer, current, 3, 5, Map.of(
                4, Map.of("PHI", List.of(upcoming("w4"))),
                5, Map.of("PHI", List.of(upcoming("w5a"), upcoming("w5b")), "BOS", List.of(upcoming("w5c")))),
                Map.of(), Map.of("G", 30.0, "C", 25.0), new ArrayList<>());
        season.setReplacement(ReplacementLevels.compute(List.of("PG", "C"), 2, Map.of()));
        given(basketballValuer.load(any(), anyInt(), anyInt())).willReturn(season);

        PlannerResponse plan = new PlannerService(leagueData, calendar, valuer, basketballValuer).plan("H1", "me");

        assertThat(plan.weeks()).containsExactly(3, 4, 5);
        Row guardRow = plan.players().getFirst();
        assertThat(guardRow.cells()).extracting(Cell::games).containsExactly(2, 1, 2);
        assertThat(guardRow.cells()).extracting(Cell::opponent).containsExactly("2 games", "1 game", "2 games");
        assertThat(plan.players().get(1).cells()).extracting(Cell::status).containsExactly("ok", "bye", "ok");
        assertThat(plan.shortages()).extracting(PlannerResponse.Shortage::message).containsExactly(
                "Week 4: no available player for C.",
                "Week 5: your best lineup averages 1.5 games per starter, so there's little room to wait for a "
                        + "better game.");
    }

    private static LockInScorer.ScheduledGame upcoming(String id) {
        return new LockInScorer.ScheduledGame(id, null, "OPP", true, "pre_game");
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
