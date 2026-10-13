package com.fantasy.lockin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fantasy.league.InvalidRequestException;
import com.fantasy.league.LeagueData;
import com.fantasy.league.RosterResponse;
import com.fantasy.league.SeasonCalendar;
import com.fantasy.lockin.LockInAdviceResponse.Action;
import com.fantasy.lockin.LockInAdviceResponse.StarterAdvice;
import com.fantasy.lockin.LockInScorer.ScheduledGame;
import com.fantasy.player.PlayerSummary;
import com.fantasy.sleeper.SleeperLeague;
import com.fantasy.sleeper.SleeperRoster;
import com.fantasy.sleeper.SleeperWeeklyEntry;
import com.fantasy.sleeper.Sport;
import com.fantasy.stats.DefenseTable;
import com.fantasy.stats.WeeklyDataService;

/**
 * Six starters, one per situation. Each plays for their own team; "value" is the
 * Lock-In value of their games left.
 * <ul>
 * <li>lock: scored 50 on Monday, two games left worth 40</li>
 * <li>pass: scored 20 on Monday, two games left worth 40</li>
 * <li>wait: three games left, none played; lock 45+ after the first</li>
 * <li>done: scored 30 in their only game, none left</li>
 * <li>live: in a game now, one more after it; lock 35+ after this one</li>
 * <li>held: marked as locked at 44</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class LockInAdvisorServiceTest {

    private static final SleeperLeague HOOPS = new SleeperLeague("L1", "Hoops", "2026", "in_season", 2, null,
            List.of("PG", "SG", "G", "SF", "PF", "C", "BN"), Map.of("pts", 1.0), null, "nba");
    private static final List<String> STARTERS = List.of("lock", "pass", "wait", "done", "live", "held");

    @Mock
    private LeagueData leagueData;
    @Mock
    private SeasonCalendar calendar;
    @Mock
    private LockInScorer scorer;
    @Mock
    private WeeklyDataService weeklyData;
    @Mock
    private LockInMarkRepository marks;

    private LockInAdvisorService advisor;

    @BeforeEach
    void setUp() {
        advisor = new LockInAdvisorService(leagueData, calendar, scorer, weeklyData, marks,
                Clock.fixed(Instant.parse("2026-11-03T12:00:00Z"), ZoneOffset.UTC));
        List<PlayerSummary> players = new ArrayList<>(STARTERS.stream().map(LockInAdvisorServiceTest::player).toList());
        players.add(player("bench"));
        SleeperRoster roster = new SleeperRoster(1, "me", null, players.stream().map(PlayerSummary::playerId).toList(),
                STARTERS, null, null, null);
        LeagueData.Team team = new LeagueData.Team(roster, new RosterResponse.Owner("me", "Dani", null, null), players);
        lenient().when(leagueData.load("L1")).thenReturn(new LeagueData.Snapshot(HOOPS, List.of(team)));
        lenient().when(calendar.currentWeek(HOOPS)).thenReturn(3);
        lenient().when(scorer.load(any(), anyInt())).thenReturn(new LockInScorer.Week("2026", 3,
                Map.of("pts", 1.0), Map.of(
                        "LOCK", List.of(done("m1"), upcoming("x1"), upcoming("x2")),
                        "PASS", List.of(done("m2"), upcoming("x3"), upcoming("x4")),
                        "WAIT", List.of(upcoming("x5"), upcoming("x6"), upcoming("x7")),
                        "DONE", List.of(done("m3")),
                        "LIVE", List.of(new ScheduledGame("x8", null, "BOS", true, "in_game"), upcoming("x9")),
                        "HELD", List.of(done("m4"), upcoming("y1"))),
                Map.of(), Map.of(), DefenseTable.from(List.of()), new ArrayList<>()));
        Map<String, double[]> outlook = Map.of( // games left, value, threshold after the first game left
                "lock", new double[] {2, 40, 35}, "pass", new double[] {2, 40, 35}, "wait", new double[] {3, 50, 45},
                "done", new double[] {0, 0, 0}, "live", new double[] {2, 38, 35}, "held", new double[] {1, 30, 0});
        lenient().when(scorer.analyze(any(), any())).thenAnswer(inv -> {
            PlayerSummary p = inv.getArgument(0);
            double[] o = outlook.get(p.playerId());
            List<LockInScorer.GameOutlook> games = Collections.nCopies((int) o[0],
                    new LockInScorer.GameOutlook("g", "2026-11-05", "MIA", true, null, 30, null));
            List<Double> thresholds = new ArrayList<>(Collections.nCopies((int) o[0], 0.0));
            if (o[0] > 1) {
                thresholds.set(0, o[2]);
            }
            return new LockInScorer.Analysis(p, o[0] > 0, null, games, null, null, List.of(),
                    new LockInModel.Outcome(o[1], 0, thresholds));
        });
        // The in-progress game already has partial stats; it must not count as finished
        lenient().when(weeklyData.liveStats(Sport.NBA, "2026", 3)).thenReturn(List.of(
                box("lock", "m1", 50), box("pass", "m2", 20), box("done", "m3", 30), box("held", "m4", 12),
                box("live", "x8", 25)));
        lenient().when(marks.findByLeagueIdAndUserIdAndWeek("L1", "me", 3)).thenReturn(List.of(
                new LockInMark("L1", "me", 3, "held", "m0", "2026-11-01", "NYK", 44, Instant.EPOCH)));
    }

    @Test
    void advisesEachStarterByComparingTheirScoreWithTheValueOfWaiting() {
        List<StarterAdvice> starters = advisor.advice("L1", "me").starters();

        assertThat(starters).extracting(StarterAdvice::action).containsExactly(
                Action.LOCK, Action.PASS, Action.WAITING, Action.LAST_GAME, Action.PLAYING, Action.LOCKED);
        assertThat(starters).extracting(StarterAdvice::message).containsExactly(
                "Scored 50.0 with 2 games left. Lock it in: waiting is worth 40.0.",
                "Scored 20.0 with 2 games left. Pass: waiting is worth 40.0.",
                "3 games this week. Lock their first score if it's 45.0 or more.",
                "Scored 30.0 in their last game this week, which counts unless you locked an earlier score.",
                "live is playing now. Lock the score after this game if it's 35.0 or more.",
                "Locked in at 44.0.");
        assertThat(starters).extracting(StarterAdvice::canLock)
                .containsExactly(true, true, false, true, false, false);
        assertThat(starters.get(2).upcoming()).extracting(LockInAdviceResponse.UpcomingGame::lockAbove)
                .containsExactly(45.0, 0.0, null);
        assertThat(starters.get(5).lockedGame().points()).isEqualTo(44.0);
    }

    @Test
    void lockingRecordsTheLatestFinishedGame() {
        advisor.lock("L1", "me", "lock");

        verify(marks).save(argThat(m -> m.getPlayerId().equals("lock") && m.getPoints() == 50.0
                && m.getGameId().equals("m1") && m.getOpponent().equals("BOS")));
    }

    @Test
    void cannotLockDuringAGameBeforePlayingOrForABenchPlayer() {
        assertThatThrownBy(() -> advisor.lock("L1", "me", "live"))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("playing right now");
        assertThatThrownBy(() -> advisor.lock("L1", "me", "wait"))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("hasn't finished a game");
        assertThatThrownBy(() -> advisor.lock("L1", "me", "bench"))
                .isInstanceOf(InvalidRequestException.class).hasMessage("Only your current starters can be locked.");
        verify(marks, never()).save(any());
    }

    @Test
    void unlockingRemovesTheMark() {
        advisor.unlock("L1", "me", "held");

        verify(marks).deleteById(new LockInMark.Key("L1", "me", 3, "held"));
    }

    @Test
    void footballLeaguesAreRejected() {
        SleeperLeague football = new SleeperLeague("F1", "Gridiron", "2026", "in_season", 2, null, List.of("QB"),
                Map.of(), null);
        given(leagueData.load("F1")).willReturn(new LeagueData.Snapshot(football, List.of()));

        assertThatThrownBy(() -> advisor.advice("F1", "me")).isInstanceOf(InvalidRequestException.class);
    }

    private static PlayerSummary player(String id) {
        return new PlayerSummary(id, id, "PG", id.toUpperCase(), null);
    }

    private static ScheduledGame done(String gameId) {
        return new ScheduledGame(gameId, null, "BOS", true, "complete");
    }

    private static ScheduledGame upcoming(String gameId) {
        return new ScheduledGame(gameId, null, "MIA", false, "pre_game");
    }

    private static SleeperWeeklyEntry box(String playerId, String gameId, double points) {
        return new SleeperWeeklyEntry(playerId, playerId.toUpperCase(), "BOS", 3, Map.of("pts", points, "sp", 1500.0),
                "2026-11-02", gameId);
    }
}
