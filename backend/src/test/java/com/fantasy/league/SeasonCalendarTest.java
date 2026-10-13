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
}
