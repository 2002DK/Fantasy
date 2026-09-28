package com.fantasy.league;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fantasy.league.RosterResponse.Starter;
import com.fantasy.sleeper.SleeperClient;
import com.fantasy.sleeper.SleeperLeague;
import com.fantasy.sleeper.SleeperLeagueUser;
import com.fantasy.sleeper.SleeperRoster;

@ExtendWith(MockitoExtension.class)
class RosterServiceTest {

    private static final String LEAGUE_ID = "L1";

    @Mock
    private SleeperClient sleeperClient;

    private RosterService service;

    @BeforeEach
    void setUp() {
        service = new RosterService(sleeperClient);
    }

    @Test
    void labelsStartersBySlotAndDerivesBench() {
        givenLeague(List.of("QB", "RB", "FLEX", "DEF", "BN", "BN"));
        given(sleeperClient.getRosters(LEAGUE_ID)).willReturn(List.of(
                roster(1, "other", null, List.of("9", "8"), List.of("9"), null, null),
                roster(2, "me", null,
                        List.of("100", "200", "300", "CLE", "400", "500", "600"),
                        List.of("100", "200", "300", "CLE"),
                        List.of("500"),
                        List.of("600"))));
        given(sleeperClient.getLeagueUsers(LEAGUE_ID)).willReturn(List.of(
                new SleeperLeagueUser("me", "Dani", "abc", new SleeperLeagueUser.Metadata("Giant Dolphins"))));

        RosterResponse response = service.findRoster(LEAGUE_ID, "me");

        assertThat(response.rosterId()).isEqualTo(2);
        assertThat(response.starters()).containsExactly(
                new Starter("QB", "100"), new Starter("RB", "200"),
                new Starter("FLEX", "300"), new Starter("DEF", "CLE"));
        assertThat(response.bench()).containsExactly("400");
        assertThat(response.reserve()).containsExactly("500");
        assertThat(response.taxi()).containsExactly("600");
        assertThat(response.owner()).isEqualTo(new RosterResponse.Owner(
                "me", "Dani", "Giant Dolphins", "https://sleepercdn.com/avatars/thumbs/abc"));
        assertThat(response.record()).isEqualTo(new RosterResponse.TeamRecord(7, 6, 0, 1776.06, 1695.36));
    }

    @Test
    void emptyStartingSlotHasNullPlayer() {
        givenLeague(List.of("QB", "RB", "BN"));
        given(sleeperClient.getRosters(LEAGUE_ID)).willReturn(List.of(
                roster(1, "me", null, List.of("100"), List.of("100", "0"), null, null)));
        given(sleeperClient.getLeagueUsers(LEAGUE_ID)).willReturn(List.of());

        RosterResponse response = service.findRoster(LEAGUE_ID, "me");

        assertThat(response.starters()).containsExactly(new Starter("QB", "100"), new Starter("RB", null));
        assertThat(response.bench()).isEmpty();
    }

    @Test
    void coOwnerGetsSharedRosterWithPrimaryOwnerDetails() {
        givenLeague(List.of("QB"));
        given(sleeperClient.getRosters(LEAGUE_ID)).willReturn(List.of(
                roster(4, "owner", List.of("co"), List.of("100"), List.of("100"), null, null)));
        given(sleeperClient.getLeagueUsers(LEAGUE_ID)).willReturn(List.of(
                new SleeperLeagueUser("owner", "Sunny", null, null)));

        RosterResponse response = service.findRoster(LEAGUE_ID, "co");

        assertThat(response.rosterId()).isEqualTo(4);
        assertThat(response.owner().displayName()).isEqualTo("Sunny");
        assertThat(response.owner().teamName()).isNull();
    }

    @Test
    void unknownLeagueIsNotFound() {
        given(sleeperClient.getLeague("404")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.findRoster("404", "me"))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("No Sleeper league found with ID '404'");
    }

    @Test
    void userWithoutRosterIsNotFound() {
        givenLeague(List.of("QB"));
        given(sleeperClient.getRosters(LEAGUE_ID)).willReturn(List.of(
                roster(1, "someone", null, List.of(), List.of(), null, null)));

        assertThatThrownBy(() -> service.findRoster(LEAGUE_ID, "me"))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("User 'me' has no roster in league 'Dynasty'");
    }

    private void givenLeague(List<String> rosterPositions) {
        given(sleeperClient.getLeague(LEAGUE_ID)).willReturn(Optional.of(
                new SleeperLeague(LEAGUE_ID, "Dynasty", "2026", "in_season", 12, null, rosterPositions)));
    }

    private static SleeperRoster roster(int id, String ownerId, List<String> coOwners, List<String> players,
            List<String> starters, List<String> reserve, List<String> taxi) {
        return new SleeperRoster(id, ownerId, coOwners, players, starters, reserve, taxi,
                new SleeperRoster.Settings(7, 6, 0, 1776, 6, 1695, 36));
    }
}
