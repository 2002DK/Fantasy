package com.fantasy.sleeper;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Weekly stats, projections and the NFL schedule. These live on Sleeper's
 * api.sleeper.com host, which is not part of the documented public API and may
 * change without notice; callers must tolerate failures.
 */
@Component
public class SleeperStatsClient {

    /** Fantasy-relevant positions; without this filter the response includes every IDP (~2 MB more). */
    private static final List<String> POSITIONS = List.of("QB", "RB", "WR", "TE", "K", "DEF");

    private static final ParameterizedTypeReference<List<SleeperWeeklyEntry>> ENTRIES =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;

    public SleeperStatsClient(RestClient.Builder builder, @Value("${sleeper.stats-base-url}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    public List<SleeperWeeklyEntry> getProjections(String season, int week) {
        return getWeekly("projections", season, week);
    }

    public List<SleeperWeeklyEntry> getStats(String season, int week) {
        return getWeekly("stats", season, week);
    }

    public List<SleeperGame> getSchedule(String season) {
        List<SleeperGame> games = restClient.get()
                .uri("/schedule/nfl/regular/{season}", season)
                .retrieve()
                .body(new ParameterizedTypeReference<List<SleeperGame>>() {});
        return games != null ? games : List.of();
    }

    private List<SleeperWeeklyEntry> getWeekly(String kind, String season, int week) {
        List<SleeperWeeklyEntry> entries = restClient.get()
                .uri(uri -> uri.path("/{kind}/nfl/{season}/{week}")
                        .queryParam("season_type", "regular")
                        .queryParam("position[]", POSITIONS.toArray())
                        .build(kind, season, week))
                .retrieve()
                .body(ENTRIES);
        return entries != null ? entries : List.of();
    }
}
