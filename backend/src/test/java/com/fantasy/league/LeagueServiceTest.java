package com.fantasy.league;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fantasy.sleeper.SeasonState;
import com.fantasy.sleeper.SleeperClient;
import com.fantasy.sleeper.SleeperLeague;
import com.fantasy.sleeper.SleeperUser;
import com.fantasy.sleeper.Sport;

@ExtendWith(MockitoExtension.class)
class LeagueServiceTest {

    @Mock
    private SleeperClient sleeperClient;

    @Test
    void listsFootballAndBasketballLeaguesTaggedBySport() {
        given(sleeperClient.getUser("dani")).willReturn(Optional.of(new SleeperUser("123", "dani", "Dani", null)));
        given(sleeperClient.getState(Sport.NFL)).willReturn(new SeasonState("2026", 6, "regular"));
        given(sleeperClient.getState(Sport.NBA)).willReturn(new SeasonState("2026", 2, "pre"));
        given(sleeperClient.getLeagues("123", Sport.NFL, "2026")).willReturn(List.of(league("F1", "nfl")));
        given(sleeperClient.getLeagues("123", Sport.NBA, "2026")).willReturn(List.of(league("B1", "nba")));

        UserLeaguesResponse response = new LeagueService(sleeperClient).findLeagues("dani", null);

        assertThat(response.season()).isEqualTo("2026");
        assertThat(response.leagues()).extracting(UserLeaguesResponse.League::leagueId, UserLeaguesResponse.League::sport)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("F1", "nfl"),
                        org.assertj.core.groups.Tuple.tuple("B1", "nba"));
    }

    @Test
    void anExplicitSeasonAppliesToBothSports() {
        given(sleeperClient.getUser("dani")).willReturn(Optional.of(new SleeperUser("123", "dani", "Dani", null)));
        given(sleeperClient.getLeagues("123", Sport.NFL, "2025")).willReturn(List.of());
        given(sleeperClient.getLeagues("123", Sport.NBA, "2025")).willReturn(List.of(league("B1", "nba")));

        UserLeaguesResponse response = new LeagueService(sleeperClient).findLeagues("dani", "2025");

        assertThat(response.season()).isEqualTo("2025");
        assertThat(response.leagues()).singleElement().extracting(UserLeaguesResponse.League::sport).isEqualTo("nba");
        verify(sleeperClient, never()).getState(Sport.NBA);
    }

    private static SleeperLeague league(String id, String sport) {
        return new SleeperLeague(id, id, "2026", "in_season", 10, null, List.of(), java.util.Map.of(), null, sport);
    }
}
