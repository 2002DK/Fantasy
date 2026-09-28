package com.fantasy.sleeper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Thin wrapper over Sleeper's public read-only API. No auth key is required.
 */
@Component
public class SleeperClient {

    private final RestClient restClient;

    public SleeperClient(RestClient.Builder builder, @Value("${sleeper.base-url}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    public NflState getNflState() {
        return restClient.get()
                .uri("/state/nfl")
                .retrieve()
                .body(NflState.class);
    }

    /** Sleeper answers an unknown username with HTTP 200 and a literal {@code null} body. */
    public Optional<SleeperUser> getUser(String username) {
        SleeperUser user = restClient.get()
                .uri("/user/{username}", username)
                .retrieve()
                .body(SleeperUser.class);
        return Optional.ofNullable(user);
    }

    public List<SleeperLeague> getLeagues(String userId, String season) {
        List<SleeperLeague> leagues = restClient.get()
                .uri("/user/{userId}/leagues/nfl/{season}", userId, season)
                .retrieve()
                .body(new ParameterizedTypeReference<List<SleeperLeague>>() {});
        return leagues != null ? leagues : List.of();
    }

    /** Unlike users, Sleeper answers an unknown league with a real 404. */
    public Optional<SleeperLeague> getLeague(String leagueId) {
        try {
            SleeperLeague league = restClient.get()
                    .uri("/league/{leagueId}", leagueId)
                    .retrieve()
                    .body(SleeperLeague.class);
            return Optional.ofNullable(league);
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    public List<SleeperRoster> getRosters(String leagueId) {
        List<SleeperRoster> rosters = restClient.get()
                .uri("/league/{leagueId}/rosters", leagueId)
                .retrieve()
                .body(new ParameterizedTypeReference<List<SleeperRoster>>() {});
        return rosters != null ? rosters : List.of();
    }

    /**
     * The full NFL player database (~15 MB), keyed by player ID. Sleeper asks callers
     * to fetch this at most once a day; see {@code PlayerSyncService}.
     */
    public Map<String, SleeperPlayer> getAllPlayers() {
        Map<String, SleeperPlayer> players = restClient.get()
                .uri("/players/nfl")
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, SleeperPlayer>>() {});
        return players != null ? players : Map.of();
    }

    public List<SleeperLeagueUser> getLeagueUsers(String leagueId) {
        List<SleeperLeagueUser> users = restClient.get()
                .uri("/league/{leagueId}/users", leagueId)
                .retrieve()
                .body(new ParameterizedTypeReference<List<SleeperLeagueUser>>() {});
        return users != null ? users : List.of();
    }
}
