package com.fantasy.league;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fantasy.sleeper.SeasonState;
import com.fantasy.sleeper.SleeperClient;
import com.fantasy.sleeper.SleeperLeague;
import com.fantasy.sleeper.Sport;

@ExtendWith(MockitoExtension.class)
class SeasonCalendarTest {

    private static final SleeperLeague HOOPS = new SleeperLeague("H1", "Hoops", "2026", "pre_draft", 10, null,
            List.of(), Map.of(), null, "nba");

    @Mock
    private SleeperClient sleeperClient;

    @Test
    void basketballPreseasonIsWeekOne() {
        // Sleeper counts preseason weeks too: "week 3" a week before opening night
        given(sleeperClient.getState(Sport.NBA)).willReturn(new SeasonState("2026", 3, "pre"));

        assertThat(new SeasonCalendar(sleeperClient).currentWeek(HOOPS)).isEqualTo(1);
    }

    @Test
    void basketballRegularSeasonWeekIsCappedAtTheLastScheduledWeek() {
        given(sleeperClient.getState(Sport.NBA)).willReturn(new SeasonState("2026", 27, "regular"));

        assertThat(new SeasonCalendar(sleeperClient).currentWeek(HOOPS)).isEqualTo(SeasonCalendar.LAST_NBA_WEEK);
    }

    @Test
    void basketballSeasonLengthFollowsItsPlayoffSettings() {
        SleeperLeague set = new SleeperLeague("H2", "Hoops", "2026", "in_season", 10, null, List.of(), Map.of(),
                new SleeperLeague.Settings(22, 6, 0), "nba");
        SleeperLeague unset = new SleeperLeague("H3", "Hoops", "2026", "in_season", 10, null, List.of(), Map.of(),
                null, "nba");

        assertThat(SeasonCalendar.regularSeasonEnd(set)).isEqualTo(21);
        assertThat(SeasonCalendar.lastFantasyWeek(set)).isEqualTo(24); // 6 teams: 3 rounds
        assertThat(SeasonCalendar.regularSeasonEnd(unset)).isEqualTo(20);
        assertThat(SeasonCalendar.lastFantasyWeek(unset)).isEqualTo(23);
        // Never past the last scheduled week
        assertThat(SeasonCalendar.lastFantasyWeek(new SleeperLeague("H4", "Hoops", "2026", "in_season", 10, null,
                List.of(), Map.of(), new SleeperLeague.Settings(24, 8, 0), "nba"))).isEqualTo(25);
    }

    @Test
    void footballSeasonLengthIsUnchanged() {
        SleeperLeague football = new SleeperLeague("F1", "Gridiron", "2026", "in_season", 12, null, List.of(),
                Map.of(), new SleeperLeague.Settings(15, 6, 0));

        assertThat(SeasonCalendar.lastFantasyWeek(football)).isEqualTo(17);
        assertThat(SeasonCalendar.regularSeasonEnd(football)).isEqualTo(14);
    }
}
