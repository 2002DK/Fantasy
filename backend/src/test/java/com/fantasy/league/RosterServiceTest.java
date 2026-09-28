package com.fantasy.league;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fantasy.league.RosterResponse.Starter;
import com.fantasy.player.PlayerService;
import com.fantasy.player.PlayerSummary;
import com.fantasy.sleeper.SleeperClient;
import com.fantasy.sleeper.SleeperLeague;
import com.fantasy.sleeper.SleeperLeagueUser;
import com.fantasy.sleeper.SleeperRoster;

@ExtendWith(MockitoExtension.class)
class RosterServiceTest {

    private static final String LEAGUE_ID = "L1";

    @Mock
    private SleeperClient sleeperClient;

    @Mock
    private PlayerService playerService;

    private RosterService service;

    @BeforeEach
    void setUp() {
        service = new RosterService(sleeperClient, playerService);
        // Every ID resolves to a player named "P<id>"
        lenient().when(playerService.findSummaries(any())).thenAnswer(invocation -> {
            Collection<String> ids = invocation.getArgument(0);
            return ids.stream().collect(Collectors.toMap(Function.identity(), RosterServiceTest::player));
        });
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
                new Starter("QB", player("100")), new Starter("RB", player("200")),
                new Starter("FLEX", player("300")), new Starter("DEF", player("CLE")));
        assertThat(response.bench()).containsExactly(player("400"));
        assertThat(response.reserve()).containsExactly(player("500"));
        assertThat(response.taxi()).containsExactly(player("600"));
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

        assertThat(response.starters()).containsExactly(new Starter("QB", player("100")), new Starter("RB", null));
        assertThat(response.bench()).isEmpty();
    }

    @Test
    void resolvesAllPlayersInOneLookupWithoutTheEmptySlotMarker() {
        givenLeague(List.of("QB", "RB", "BN"));
        given(sleeperClient.getRosters(LEAGUE_ID)).willReturn(List.of(
                roster(1, "me", null, List.of("100", "400"), List.of("100", "0"), null, null)));
        given(sleeperClient.getLeagueUsers(LEAGUE_ID)).willReturn(List.of());

        service.findRoster(LEAGUE_ID, "me");

        verify(playerService).findSummaries(
                argThat(ids -> ids.size() == 2 && ids.containsAll(List.of("100", "400"))));
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

    private static PlayerSummary player(String id) {
        return new PlayerSummary(id, "P" + id, "RB", "PHI", null);
    }

    private static SleeperRoster roster(int id, String ownerId, List<String> coOwners, List<String> players,
            List<String> starters, List<String> reserve, List<String> taxi) {
        return new SleeperRoster(id, ownerId, coOwners, players, starters, reserve, taxi,
                new SleeperRoster.Settings(7, 6, 0, 1776, 6, 1695, 36));
    }
}
