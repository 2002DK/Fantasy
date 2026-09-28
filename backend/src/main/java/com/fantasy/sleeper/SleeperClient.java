package com.fantasy.sleeper;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
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
}
