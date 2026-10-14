package com.fantasy.lockin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.ResourceAccessException;

import com.fantasy.lockin.LockInScorer.Analysis;
import com.fantasy.lockin.LockInScorer.GameOutlook;
import com.fantasy.player.PlayerService;
import com.fantasy.player.PlayerSummary;
import com.fantasy.sleeper.SleeperGame;
import com.fantasy.sleeper.SleeperLeague;
import com.fantasy.sleeper.SleeperWeeklyEntry;
import com.fantasy.sleeper.Sport;
import com.fantasy.stats.DefenseTable;
import com.fantasy.stats.WeeklyDataService;

@ExtendWith(MockitoExtension.class)
class LockInScorerTest {

    private static final SleeperLeague HOOPS = new SleeperLeague("L1", "Hoops", "2026", "in_season", 10, null,
            List.of("PG", "UTIL", "BN"), Map.of("pts", 1.0), null, "nba");
    private static final PlayerSummary GUARD = new PlayerSummary("7", "Guard", "PG", "PHI", null);

    @Mock
    private WeeklyDataService weeklyData;
    @Mock
    private PlayerService playerService;

    private LockInScorer scorer;

    @BeforeEach
    void setUp() {
        scorer = new LockInScorer(weeklyData, new LockInService(weeklyData), playerService);
        // PHI plays three games in week 3; the first is over
        lenient().when(weeklyData.schedule(Sport.NBA, "2026")).thenReturn(List.of(
                new SleeperGame("g1", 3, "PHI", "BOS", "complete", "2026-11-02"),
                new SleeperGame("g2", 3, "NYK", "PHI", "pre_game", "2026-11-04"),
                new SleeperGame("g3", 3, "PHI", "MIA", "pre_game", "2026-11-06"),
                new SleeperGame("g9", 4, "PHI", "MIA", "pre_game", "2026-11-09")));
        lenient().when(playerService.findSummaries(eq(Sport.NBA), any())).thenReturn(Map.of(
                "7", GUARD, "8", new PlayerSummary("8", "Other guard", "PG", "BOS", null)));
        lenient().when(weeklyData.stats(eq(Sport.NBA), eq("2026"), anyInt())).thenReturn(List.of());
    }

    @Test
    void blendsProjectionAndFormForEachGameLeft() {
        given(weeklyData.projections(Sport.NBA, "2026", 3)).willReturn(List.of(
                projection("7", "g1", "2026-11-02", "BOS", 30),
                projection("7", "g2", "2026-11-04", "NYK", 30),
                projection("7", "g3", "2026-11-06", "MIA", 40)));
        // Two past games averaging 20; no matchup data for these opponents
        given(weeklyData.stats(Sport.NBA, "2026", 2)).willReturn(List.of(
                boxScore("7", "2026-10-28", "ATL", 18), boxScore("7", "2026-10-30", "CHI", 22)));

        Analysis analysis = scorer.analyze(GUARD, scorer.load(HOOPS, 3));

        // The finished game is gone; 0.6 × projection + 0.4 × 20 for the other two
        assertThat(analysis.games()).extracting(GameOutlook::gameId).containsExactly("g2", "g3");
        assertThat(analysis.games()).extracting(GameOutlook::expectedPoints).containsExactly(26.0, 32.0);
        assertThat(analysis.games().getFirst().opponent()).isEqualTo("NYK");
        assertThat(analysis.games().getFirst().home()).isFalse();
        assertThat(analysis.value()).isEqualTo(LockInModel.weeklyValue(List.of(26.0, 32.0)));
        assertThat(analysis.projectedPerGame()).isEqualTo(35.0);
        assertThat(analysis.recentAverage()).isEqualTo(20.0);
        assertThat(analysis.available()).isTrue();
    }

    @Test
    void aScheduledGameWithoutAProjectionIsOneThePlayerIsExpectedToMiss() {
        given(weeklyData.projections(Sport.NBA, "2026", 3)).willReturn(List.of(
                projection("7", "g3", "2026-11-06", "MIA", 40)));

        Analysis analysis = scorer.analyze(GUARD, scorer.load(HOOPS, 3));

        assertThat(analysis.games()).extracting(GameOutlook::gameId).containsExactly("g3");
        assertThat(analysis.value()).isEqualTo(40.0);
    }

    @Test
    void playersWithoutProjectionsUseFormOnTheirTeamsSchedule() {
        given(weeklyData.projections(Sport.NBA, "2026", 3)).willReturn(List.of());
        given(weeklyData.stats(Sport.NBA, "2026", 2)).willReturn(List.of(boxScore("7", "2026-10-28", "ATL", 20)));

        Analysis analysis = scorer.analyze(GUARD, scorer.load(HOOPS, 3));

        assertThat(analysis.games()).extracting(GameOutlook::expectedPoints).containsExactly(20.0, 20.0);
        assertThat(analysis.projectedPerGame()).isNull();
    }

    @Test
    void formIsScaledByHowManyPointsTheOpponentAllowsToThePosition() {
        given(weeklyData.projections(Sport.NBA, "2026", 3)).willReturn(List.of());
        // NYK allowed 30 a game to point guards, MIA 10; the player averages 20
        given(weeklyData.stats(Sport.NBA, "2026", 2)).willReturn(List.of(
                boxScore("7", "2026-10-28", "ATL", 20),
                boxScore("8", "2026-10-29", "NYK", 30),
                boxScore("8", "2026-10-31", "MIA", 10)));

        Analysis analysis = scorer.analyze(GUARD, scorer.load(HOOPS, 3));

        GameOutlook atNewYork = analysis.games().getFirst();
        assertThat(atNewYork.matchup().rank()).isEqualTo(1);
        assertThat(atNewYork.expectedPoints()).isCloseTo(23.0, within(1e-9)); // +15% cap
        assertThat(analysis.games().get(1).expectedPoints()).isCloseTo(17.0, within(1e-9)); // −15% cap
    }

    @Test
    void outSkipsTheNextGameAndDayToDayDiscountsIt() {
        given(weeklyData.projections(Sport.NBA, "2026", 3)).willReturn(List.of(
                projection("7", "g2", "2026-11-04", "NYK", 30),
                projection("7", "g3", "2026-11-06", "MIA", 40)));
        LockInScorer.Week week = scorer.load(HOOPS, 3);

        Analysis out = scorer.analyze(new PlayerSummary("7", "Guard", "PG", "PHI", "Out"), week);
        Analysis dayToDay = scorer.analyze(new PlayerSummary("7", "Guard", "PG", "PHI", "DTD"), week);

        assertThat(out.games()).extracting(GameOutlook::gameId).containsExactly("g3");
        assertThat(out.availabilityNote()).isEqualTo("Out");
        assertThat(dayToDay.games()).extracting(GameOutlook::expectedPoints).containsExactly(27.0, 40.0);
    }

    @Test
    void injuredReserveAndNoGamesLeftAreUnavailable() {
        given(weeklyData.projections(Sport.NBA, "2026", 3)).willReturn(List.of());
        LockInScorer.Week week = scorer.load(HOOPS, 3);

        Analysis reserve = scorer.analyze(new PlayerSummary("7", "Guard", "PG", "PHI", "IR"), week);
        Analysis idle = scorer.analyze(new PlayerSummary("9", "Idle", "C", "LAL", null), week);

        assertThat(reserve.available()).isFalse();
        assertThat(reserve.value()).isZero();
        assertThat(idle.available()).isFalse();
        assertThat(idle.availabilityNote()).isEqualTo("No games this week");
    }

    @Test
    void scheduleFailureFallsBackToProjectedGames() {
        given(weeklyData.schedule(Sport.NBA, "2026")).willThrow(new ResourceAccessException("timeout"));
        given(weeklyData.projections(Sport.NBA, "2026", 3)).willReturn(List.of(
                projection("7", "g2", "2026-11-04", "NYK", 30)));

        LockInScorer.Week week = scorer.load(HOOPS, 3);

        assertThat(scorer.analyze(GUARD, week).value()).isEqualTo(30.0);
        assertThat(week.notes()).anyMatch(n -> n.contains("schedule is unavailable"));
    }

    @Test
    void weekOneHasNoFormYet() {
        given(weeklyData.projections(Sport.NBA, "2026", 1)).willReturn(List.of());

        assertThat(scorer.load(HOOPS, 1).notes()).anyMatch(n -> n.contains("No games have been played yet"));
    }

    @Test
    void tracksGamesPlayedAndGamesInProgress() {
        given(weeklyData.projections(Sport.NBA, "2026", 3)).willReturn(List.of());

        LockInScorer.Week week = scorer.load(HOOPS, 3);

        assertThat(week.gamesPlayed(GUARD)).isEqualTo(1);
        assertThat(week.isPlaying(GUARD)).isFalse();
    }

    @Test
    void finishedGamesThisWeekLeaveOutGamesInProgress() {
        given(weeklyData.projections(Sport.NBA, "2026", 3)).willReturn(List.of());
        given(weeklyData.schedule(Sport.NBA, "2026")).willReturn(List.of(
                new SleeperGame("g1", 3, "PHI", "BOS", "complete", "2026-11-02"),
                new SleeperGame("g2", 3, "NYK", "PHI", "in_game", "2026-11-04")));
        given(weeklyData.liveStats(Sport.NBA, "2026", 3)).willReturn(List.of(
                new SleeperWeeklyEntry("7", "PHI", "BOS", 3, Map.of("pts", 31.0, "sp", 2000.0), "2026-11-02", "g1"),
                new SleeperWeeklyEntry("7", "PHI", "NYK", 3, Map.of("pts", 12.0, "sp", 900.0), "2026-11-04", "g2")));

        LockInScorer.Week week = scorer.load(HOOPS, 3);

        assertThat(week.playedGames("7")).extracting(LockInAdviceResponse.PlayedGame::points).containsExactly(31.0);
    }

    @Test
    void onlyAnEarlierGamesScoreProvesALock() {
        LockInScorer.Week week = new LockInScorer.Week("2026", 3, Map.of(), Map.of(), Map.of(), Map.of(),
                DefenseTable.from(List.of()), List.of(), Map.of("7", List.of(
                        new LockInAdviceResponse.PlayedGame("g1", "2026-11-02", "BOS", 44.5),
                        new LockInAdviceResponse.PlayedGame("g2", "2026-11-04", "NYK", 30.25))));

        assertThat(week.inferredLock("7", 44.5).gameId()).isEqualTo("g1");
        assertThat(week.inferredLock("7", 30.25)).isNull(); // the latest game: locked or counting by default
        assertThat(week.inferredLock("7", 0.0)).isNull();
        assertThat(week.inferredLock("7", null)).isNull();
        assertThat(week.inferredLock("8", 44.5)).isNull();
    }

    private static SleeperWeeklyEntry projection(String playerId, String gameId, String date, String opponent,
            double points) {
        return new SleeperWeeklyEntry(playerId, "PHI", opponent, 3, Map.of("gp", 1.0, "pts", points), date, gameId);
    }

    private static SleeperWeeklyEntry boxScore(String playerId, String date, String opponent, double points) {
        return new SleeperWeeklyEntry(playerId, "PHI", opponent, 2, Map.of("pts", points, "sp", 1800.0), date,
                playerId + date);
    }
}
