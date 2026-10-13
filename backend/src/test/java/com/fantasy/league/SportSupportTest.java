package com.fantasy.league;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fantasy.sleeper.SleeperLeague;

class SportSupportTest {

    @Test
    void footballOnlyToolsRefuseBasketballLeagues() {
        SleeperLeague basketball = new SleeperLeague("B1", "Hoops", "2026", "in_season", 10, null, List.of(), Map.of(),
                null, "nba");

        assertThatThrownBy(() -> SportSupport.requireFootball(basketball, "The trade analyzer"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("The trade analyzer isn't available for basketball leagues yet; it's coming in a later update.");
    }

    @Test
    void footballLeaguesPass() {
        SleeperLeague football = new SleeperLeague("F1", "Dynasty", "2026", "in_season", 12, null, List.of(), Map.of(),
                null);

        assertThatCode(() -> SportSupport.requireFootball(football, "Start/Sit")).doesNotThrowAnyException();
    }
}
