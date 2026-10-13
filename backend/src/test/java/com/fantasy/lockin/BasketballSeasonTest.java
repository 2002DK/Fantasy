package com.fantasy.lockin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fantasy.lineup.TeamProjector.WeekProjection;
import com.fantasy.lockin.LockInScorer.ScheduledGame;
import com.fantasy.lockin.LockInService.Game;
import com.fantasy.lockin.LockInService.PlayerWeek;
import com.fantasy.player.PlayerSummary;
import com.fantasy.sleeper.SleeperWeeklyEntry;
import com.fantasy.startsit.StartSitResponse.RecentGame;
import com.fantasy.stats.DefenseTable;
import com.fantasy.trade.ReplacementLevels;
import com.fantasy.trade.TradeResponse.TradePlayer;

/**
 * Weeks 3 (current) to 5. PHI plays two games each week. This week the scorer says
 * the guard's two games are worth 45; next week (4) the projections list only one of
 * PHI's two games for the guard; week 5 uses his 30-point season average.
 */
@ExtendWith(MockitoExtension.class)
class BasketballSeasonTest {

    private static final PlayerSummary GUARD = new PlayerSummary("7", "Guard", "PG", "PHI", null);
    private static final PlayerSummary CENTER = new PlayerSummary("9", "Center", "C", "PHI", null);

    @Mock
    private LockInScorer scorer;

    private final Map<Integer, Map<String, List<ScheduledGame>>> schedule = Map.of(
            4, Map.of("PHI", List.of(game("a1", "2026-11-09", "BOS"), game("a2", "2026-11-11", "NYK"))),
            5, Map.of("PHI", List.of(game("b1", "2026-11-16", "MIA"), game("b2", "2026-11-18", "ATL"))));
    private final Map<String, PlayerWeek> nextWeek = Map.of("7", new PlayerWeek("7",
            List.of(new Game("a1", LocalDate.parse("2026-11-09"), "BOS", 40)), LockInModel.evaluate(List.of(40.0))));

    @BeforeEach
    void setUp() {
        lenient().when(scorer.analyze(any(), any())).thenAnswer(inv -> {
            PlayerSummary p = inv.getArgument(0);
            List<LockInScorer.GameOutlook> games = List.of(
                    new LockInScorer.GameOutlook("c1", "2026-11-03", "DAL", true, 36.0, 36, null),
                    new LockInScorer.GameOutlook("c2", "2026-11-05", "LAL", false, 36.0, 36, null));
            return new LockInScorer.Analysis(p, true, null, games, 36.0, null, List.of(),
                    new LockInModel.Outcome(45, 100, List.of(36.0, 0.0)));
        });
    }

    @Test
    void weeksUseThisWeeksScorerThenNextWeeksGamesThenTheSeasonAverage() {
        BasketballSeason season = season(Map.of(), DefenseTable.from(List.of()));

        List<BasketballSeason.Week> weeks = season.weeks(GUARD);

        assertThat(weeks).extracting(BasketballSeason.Week::week).containsExactly(3, 4, 5);
        assertThat(weeks).extracting(BasketballSeason.Week::games).containsExactly(2, 1, 2);
        assertThat(weeks.get(0).outcome().value()).isEqualTo(45.0);
        assertThat(weeks.get(1).outcome().value()).isEqualTo(40.0); // only the projected game
        assertThat(weeks.get(2).outcome().value()).isEqualTo(LockInModel.weeklyValue(List.of(30.0, 30.0)));
        assertThat(season.restOfSeason(GUARD)).isCloseTo(45 + 40 + LockInModel.weeklyValue(List.of(30.0, 30.0)),
                within(1e-9));
    }

    @Test
    void formIsBlendedAndScaledByEachOpponentsPointsAllowed() {
        // The guard averages 20; MIA allows twice the league average to point guards (capped at +15%)
        DefenseTable defenses = DefenseTable.fromBoxScores(List.of(
                box("8", "MIA", 40), box("8", "ATL", 20), box("8", "BOS", 20), box("8", "NYK", 0)),
                Map.of("8", "PG"), Map.of("pts", 1.0));
        BasketballSeason season = season(Map.of("7", List.of(new RecentGame(2, "CHI", 20))), defenses);

        BasketballSeason.Week week5 = season.week(GUARD, 5);

        double atMiami = 0.7 * 30 + 0.3 * 20 * 1.15;
        double atAtlanta = 0.7 * 30 + 0.3 * 20 * 1.0;
        assertThat(week5.outcome().value()).isCloseTo(LockInModel.weeklyValue(List.of(atMiami, atAtlanta)),
                within(1e-9));
    }

    @Test
    void injuredReserveCountsOnlyProjectedGamesOfTheNextWeek() {
        BasketballSeason season = season(Map.of(), DefenseTable.from(List.of()));
        PlayerSummary hurt = new PlayerSummary("7", "Guard", "PG", "PHI", "IR");

        assertThat(season.week(hurt, 4).games()).isEqualTo(1);
        assertThat(season.week(hurt, 5).games()).isZero();
        assertThat(season.evaluate(hurt).note()).isEqualTo("IR");
    }

    @Test
    void tradeValueIsRestOfSeasonAboveReplacement() {
        BasketballSeason season = season(Map.of(), DefenseTable.from(List.of()));
        // One PG slot per team in a 1-team league: the second-best PG (the center's 0) sets replacement
        season.setReplacement(ReplacementLevels.compute(List.of("PG", "C"), 1,
                Map.of("PG", List.of(150.0, 100.0), "C", List.of(90.0))));

        TradePlayer guard = season.evaluate(GUARD);

        assertThat(guard.remainingGames()).isEqualTo(5);
        assertThat(guard.replacementPoints()).isEqualTo(100.0);
        assertThat(guard.value()).isCloseTo(guard.restOfSeasonPoints() - 100, within(0.01));
    }

    @Test
    void positionsWithoutASlotHaveNoValue() {
        BasketballSeason season = season(Map.of(), DefenseTable.from(List.of()));
        season.setReplacement(ReplacementLevels.compute(List.of("PG"), 10, Map.of()));

        TradePlayer center = season.evaluate(CENTER);

        assertThat(center.value()).isZero();
        assertThat(center.note()).isEqualTo("No starting slot for C");
    }

    @Test
    void teamProjectionsCarryTheLockInSpread() {
        BasketballSeason season = season(Map.of(), DefenseTable.from(List.of()));

        WeekProjection thisWeek = season.project(GUARD, 3);

        assertThat(thisWeek).isEqualTo(new WeekProjection(45, 100));
        assertThat(season.project(GUARD, 9)).isEqualTo(new WeekProjection(0, 0));
    }

    @Test
    void upcomingGamesCoverThisWeekAndNext() {
        BasketballSeason season = season(Map.of(), DefenseTable.from(List.of()));

        List<BasketballSeason.UpcomingGame> games = season.upcomingGames(GUARD);

        assertThat(games).extracting(BasketballSeason.UpcomingGame::date)
                .containsExactly("2026-11-03", "2026-11-05", "2026-11-09", "2026-11-11");
        // Next week's second game has no projection: he's expected to miss it
        assertThat(games).extracting(BasketballSeason.UpcomingGame::projected).containsExactly(36.0, 36.0, 40.0, null);
    }

    private BasketballSeason season(Map<String, List<RecentGame>> recent, DefenseTable defenses) {
        LockInScorer.Week current = new LockInScorer.Week("2026", 3, Map.of("pts", 1.0), Map.of(), Map.of(), recent,
                defenses, new ArrayList<>());
        BasketballSeason season = new BasketballSeason(scorer, current, 3, 5, schedule, nextWeek,
                Map.of("7", 30.0, "9", 25.0), new ArrayList<>());
        season.setReplacement(ReplacementLevels.compute(List.of("PG", "C"), 10, Map.of()));
        return season;
    }

    private static ScheduledGame game(String id, String date, String opponent) {
        return new ScheduledGame(id, LocalDate.parse(date), opponent, true, "pre_game");
    }

    private static SleeperWeeklyEntry box(String playerId, String opponent, double points) {
        return new SleeperWeeklyEntry(playerId, "PHI", opponent, 2, Map.of("pts", points, "sp", 1500.0), "2026-11-01",
                opponent + playerId);
    }
}
