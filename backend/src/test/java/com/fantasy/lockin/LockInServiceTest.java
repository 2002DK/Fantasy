package com.fantasy.lockin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.BDDMockito.given;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fantasy.lockin.LockInService.Game;
import com.fantasy.lockin.LockInService.PlayerWeek;
import com.fantasy.sleeper.SleeperLeague;
import com.fantasy.sleeper.SleeperWeeklyEntry;
import com.fantasy.sleeper.Sport;
import com.fantasy.stats.WeeklyDataService;

@ExtendWith(MockitoExtension.class)
class LockInServiceTest {

    private static final Map<String, Double> SCORING = Map.of("pts", 1.0, "reb", 1.2, "ast", 1.5, "to", -1.0);
    private static final SleeperLeague HOOPS = new SleeperLeague("L1", "Hoops", "2026", "in_season", 10, null,
            List.of("PG", "UTIL", "BN"), SCORING, null, "nba");

    @Mock
    private WeeklyDataService weeklyData;

    @Test
    void scoresEachProjectedGameWithLeagueScoringInPlayOrder() {
        given(weeklyData.projections(Sport.NBA, "2026", 1)).willReturn(List.of(
                game("1362", "2026-10-22", "g2", "CLE", 20, 5, 4),
                game("1362", "2026-10-20", "g1", "NYK", 18, 5, 6),
                game("4", "2026-10-21", "g3", "LAL", 30, 10, 8)));

        Map<String, PlayerWeek> weeks = new LockInService(weeklyData).projectWeek(HOOPS, 1);

        PlayerWeek embiid = weeks.get("1362");
        // 18 + 5×1.2 + 6×1.5 − 2 turnovers = 31; 20 + 6 + 6 − 2 = 30
        assertThat(embiid.games()).containsExactly(
                new Game("g1", LocalDate.parse("2026-10-20"), "NYK", 31.0),
                new Game("g2", LocalDate.parse("2026-10-22"), "CLE", 30.0));
        assertThat(embiid.outcome().value()).isEqualTo(LockInModel.weeklyValue(List.of(31.0, 30.0)));
        assertThat(weeks.get("4").outcome().value()).isCloseTo(52.0, within(1e-9));
        assertThat(weeks).hasSize(2);
    }

    @Test
    void aRepeatedRowForTheSameGameCountsOnce() {
        given(weeklyData.projections(Sport.NBA, "2026", 1)).willReturn(List.of(
                game("1362", "2026-10-20", "g1", "NYK", 18, 5, 6),
                game("1362", "2026-10-20", "g1", "NYK", 18, 5, 6)));

        PlayerWeek week = new LockInService(weeklyData).projectWeek(HOOPS, 1).get("1362");

        assertThat(week.games()).hasSize(1);
        assertThat(week.outcome().value()).isEqualTo(31.0);
    }

    @Test
    void theRestOfTheWeekLeavesOutGamesAlreadyPlayed() {
        given(weeklyData.projections(Sport.NBA, "2026", 1)).willReturn(List.of(
                game("1362", "2026-10-20", "g1", "NYK", 18, 5, 6),
                game("1362", "2026-10-22", "g2", "CLE", 20, 5, 4),
                game("1362", "2026-10-24", "g3", "MIL", 20, 5, 4)));
        PlayerWeek week = new LockInService(weeklyData).projectWeek(HOOPS, 1).get("1362");

        PlayerWeek rest = week.from(LocalDate.parse("2026-10-22"));

        assertThat(rest.games()).extracting(Game::gameId).containsExactly("g2", "g3");
        assertThat(rest.outcome().value()).isEqualTo(LockInModel.weeklyValue(List.of(30.0, 30.0)));
        assertThat(rest.outcome().value()).isLessThan(week.outcome().value());
    }

    @Test
    void footballLeaguesAreRejected() {
        SleeperLeague football = new SleeperLeague("F1", "Gridiron", "2026", "in_season", 12, null,
                List.of("QB"), Map.of(), null);

        assertThatThrownBy(() -> new LockInService(weeklyData).projectWeek(football, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static SleeperWeeklyEntry game(String playerId, String date, String gameId, String opponent,
            double points, double rebounds, double assists) {
        return new SleeperWeeklyEntry(playerId, "PHI", opponent, 1,
                Map.of("gp", 1.0, "pts", points, "reb", rebounds, "ast", assists, "to", 2.0), date, gameId);
    }
}
