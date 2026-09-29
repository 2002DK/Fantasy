package com.fantasy.standings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fantasy.standings.SeasonSimulator.Result;
import com.fantasy.standings.SeasonSimulator.TeamStart;
import com.fantasy.standings.SeasonSimulator.WeekGames;

class SeasonSimulatorTest {

    private static final List<int[]> PAIRS = List.of(new int[] {1, 2}, new int[] {3, 4});

    @Test
    void oddsAddUpToThePlayoffSpots() {
        List<TeamStart> teams = List.of(team(1, 3), team(2, 2), team(3, 1), team(4, 0));
        Result result = SeasonSimulator.simulate(teams, List.of(week(100, 100, 100, 100)), 2, false, 5_000, 1);

        assertThat(result.playoffOdds().values().stream().mapToDouble(Double::doubleValue).sum()).isCloseTo(2, within(1e-9));
    }

    @Test
    void aTeamThatCannotBeCaughtAlwaysMakesIt() {
        // Team 1 leads by three wins with two weeks left; team 4 can't reach the top two
        List<TeamStart> teams = List.of(team(1, 8), team(2, 5), team(3, 5), team(4, 2));
        Result result = SeasonSimulator.simulate(teams, List.of(week(100, 100, 100, 100), week(100, 100, 100, 100)),
                2, false, 5_000, 7);

        assertThat(result.playoffOdds().get(1)).isEqualTo(1.0);
        assertThat(result.playoffOdds().get(4)).isEqualTo(0.0);
        assertThat(result.averageWins().get(1)).isBetween(8.0, 10.0);
    }

    @Test
    void strongerProjectionsWinMoreOftenFromTheSameRecord() {
        List<TeamStart> teams = List.of(team(1, 0), team(2, 0), team(3, 0), team(4, 0));
        List<WeekGames> weeks = List.of(week(130, 90, 100, 100), week(130, 90, 100, 100), week(130, 90, 100, 100));
        Result result = SeasonSimulator.simulate(teams, weeks, 2, false, 5_000, 3);

        assertThat(result.playoffOdds().get(1)).isGreaterThan(0.85);
        assertThat(result.playoffOdds().get(2)).isLessThan(0.2);
        assertThat(result.averageWins().get(1)).isGreaterThan(result.averageWins().get(2));
    }

    @Test
    void medianGameAddsASecondResultEachWeek() {
        List<TeamStart> teams = List.of(team(1, 0), team(2, 0), team(3, 0), team(4, 0));
        Result result = SeasonSimulator.simulate(teams, List.of(week(100, 100, 100, 100)), 2, true, 2_000, 5);

        // Each week hands out 2 head-to-head wins and 2 median wins among 4 teams: 1 win per team on average
        double totalWins = result.averageWins().values().stream().mapToDouble(Double::doubleValue).sum();
        assertThat(totalWins).isCloseTo(4, within(1e-9));
    }

    @Test
    void sameSeedGivesTheSameOdds() {
        List<TeamStart> teams = List.of(team(1, 1), team(2, 1), team(3, 1), team(4, 1));
        List<WeekGames> weeks = List.of(week(110, 100, 105, 95));
        assertThat(SeasonSimulator.simulate(teams, weeks, 2, false, 1_000, 42).playoffOdds())
                .isEqualTo(SeasonSimulator.simulate(teams, weeks, 2, false, 1_000, 42).playoffOdds());
    }

    private static TeamStart team(int rosterId, double wins) {
        return new TeamStart(rosterId, wins, 100 * wins);
    }

    private static WeekGames week(double m1, double m2, double m3, double m4) {
        return new WeekGames(PAIRS, Map.of(1, m1, 2, m2, 3, m3, 4, m4),
                Map.of(1, 225.0, 2, 225.0, 3, 225.0, 4, 225.0));
    }
}
