package com.fantasy.lineup;

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
import com.fantasy.lockin.LockInMarkRepository;
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

/**
 * RB and FLEX slots. The current lineup starts an Out RB (0) and a bench-quality WR
 * (6); a healthy RB (12) sits on the bench and an IR RB (20) can't start.
 */
@ExtendWith(MockitoExtension.class)
class LineupServiceTest {

    private static final PlayerSummary OUT_RB = new PlayerSummary("out", "Out Back", "RB", "PHI", "Out");
    private static final PlayerSummary WR = new PlayerSummary("wr", "Slow Wideout", "WR", "PHI", null);
    private static final PlayerSummary BENCH_RB = new PlayerSummary("rb", "Bench Back", "RB", "PHI", null);
    private static final PlayerSummary IR_RB = new PlayerSummary("ir", "Hurt Back", "RB", "PHI", "IR");

    @Mock
    private LeagueData leagueData;
    @Mock
    private SeasonCalendar calendar;
    @Mock
    private WeeklyScorer scorer;

    @Test
    void benchesUnavailableStartersAndExplainsTheSwap() {
        SleeperLeague league = new SleeperLeague("L1", "Dynasty", "2026", "in_season", 2, null,
                List.of("RB", "FLEX", "BN", "BN", "IR"), Map.of(), null);
        SleeperRoster roster = new SleeperRoster(1, "me", null, List.of("out", "wr", "rb", "ir"), List.of("out", "wr"),
                List.of("ir"), null, null);
        LeagueData.Team team = new LeagueData.Team(roster, new RosterResponse.Owner("me", "Dani", null, null),
                List.of(OUT_RB, WR, BENCH_RB, IR_RB));
        given(leagueData.load("L1")).willReturn(new LeagueData.Snapshot(league, List.of(team)));
        given(calendar.currentWeek(league)).willReturn(5);
        given(scorer.load(any(), anyInt())).willReturn(new WeeklyScorer.Week("2026", 5, Map.of(),
                Map.of("PHI", new WeeklyScorer.Opponent("DAL", true, "pre_game")), Map.of(), List.of(),
                DefenseTable.from(List.of()), new ArrayList<>()));
        Map<String, Double> scores = Map.of("out", 0.0, "wr", 6.0, "rb", 12.0, "ir", 20.0);
        given(scorer.analyze(any(), any())).willAnswer(inv -> {
            PlayerSummary player = inv.getArgument(0);
            boolean available = player.injuryStatus() == null;
            return new PlayerAnalysis(player, available, available ? null : player.injuryStatus(), null, null,
                    List.of(), null, scores.get(player.playerId()));
        });

        LineupResponse response = new LineupService(leagueData, calendar, scorer, null, null, null)
                .optimize("L1", "me");

        assertThat(response.slots()).extracting(s -> s.optimal().player().playerId()).containsExactly("rb", "wr");
        assertThat(response.currentTotal()).isEqualTo(6.0);
        assertThat(response.optimalTotal()).isEqualTo(18.0);
        assertThat(response.gain()).isEqualTo(12.0);
        assertThat(response.changes()).containsExactly(
                "Start Bench Back (12.0) instead of Out Back (0.0), who is listed as Out: +12.0 expected points.");
        assertThat(response.slots().getFirst().optimal().opponent()).isEqualTo("vs DAL");
    }

    @Mock
    private LockInScorer lockInScorer;
    @Mock
    private SleeperClient sleeperClient;
    @Mock
    private LockInMarkRepository lockMarks;

    /**
     * PG, G and UTIL slots. Luka (PG/SG) has 50 points this week and two games left,
     * worth 40 by Lock-In, so he keeps his score and his spot; a bench center worth 35
     * replaces the 10-point center at UTIL.
     */
    @Test
    void basketballStartsTheBestLockInValuesAndKeepsStartersWithAScore() {
        PlayerSummary luka = new PlayerSummary("luka", "Luka", "PG", "DAL", null, List.of("PG", "SG"));
        PlayerSummary guard = new PlayerSummary("sg", "Guard", "SG", "BOS", null, List.of("SG", "PG"));
        PlayerSummary weak = new PlayerSummary("weak", "Weak Center", "C", "BOS", null);
        PlayerSummary big = new PlayerSummary("big", "Big Center", "C", "MIA", null, List.of("C", "PF"));
        SleeperLeague league = new SleeperLeague("L2", "Hoops", "2026", "in_season", 2, null,
                List.of("PG", "G", "UTIL", "BN"), Map.of(), null, "nba");
        SleeperRoster roster = new SleeperRoster(1, "me", null, List.of("luka", "sg", "weak", "big"),
                List.of("luka", "sg", "weak"), null, null, null);
        LeagueData.Team team = new LeagueData.Team(roster, new RosterResponse.Owner("me", "Dani", null, null),
                List.of(luka, guard, weak, big));
        given(leagueData.load("L2")).willReturn(new LeagueData.Snapshot(league, List.of(team)));
        given(calendar.currentWeek(league)).willReturn(3);
        given(sleeperClient.getMatchups("L2", 3)).willReturn(List.of(
                new SleeperMatchup(1, 1, 50.0, List.of("luka", "sg", "weak"), Map.of("luka", 50.0))));
        given(lockInScorer.load(any(), anyInt())).willReturn(new LockInScorer.Week("2026", 3, Map.of(),
                Map.of("DAL", List.of(new LockInScorer.ScheduledGame("g1", null, "BOS", true, "complete"))),
                Map.of(), Map.of(), DefenseTable.from(List.of()), new ArrayList<>()));
        Map<String, Double> values = Map.of("luka", 40.0, "sg", 30.0, "weak", 10.0, "big", 35.0);
        given(lockInScorer.analyze(any(), any())).willAnswer(inv -> {
            PlayerSummary player = inv.getArgument(0);
            double value = values.get(player.playerId());
            return new LockInScorer.Analysis(player, true, null, List.of(game(), game()), null, null, List.of(),
                    new LockInModel.Outcome(value, 0, List.of(value, 0.0)));
        });

        LineupResponse response = new LineupService(leagueData, calendar, null, lockInScorer, sleeperClient,
                lockMarks).optimize("L2", "me");

        assertThat(response.slots()).extracting(s -> s.optimal().player().playerId())
                .containsExactly("luka", "sg", "big");
        assertThat(response.currentTotal()).isEqualTo(90.0);
        assertThat(response.optimalTotal()).isEqualTo(115.0);
        assertThat(response.changes()).containsExactly(
                "Start Big Center (35.0) instead of Weak Center (10.0): +25.0 expected points.");
        LineupResponse.LineupPlayer lukaSlot = response.slots().getFirst().current();
        assertThat(lukaSlot.locked()).isTrue();
        assertThat(lukaSlot.opponent()).isEqualTo("Scored 50.0 · 2 games left");
        assertThat(response.notes()).anyMatch(n -> n.startsWith("Starters who already have a score"));
    }

    @Test
    void gamesLabelReadsNaturally() {
        assertThat(LineupService.gamesLabel(3, null)).isEqualTo("3 games left");
        assertThat(LineupService.gamesLabel(1, null)).isEqualTo("1 game left");
        assertThat(LineupService.gamesLabel(0, null)).isEqualTo("No games left");
        assertThat(LineupService.gamesLabel(0, 41.5)).isEqualTo("Scored 41.5 · no games left");
    }

    private static LockInScorer.GameOutlook game() {
        return new LockInScorer.GameOutlook("g", "2026-11-04", "NYK", true, null, 0, null);
    }
}
