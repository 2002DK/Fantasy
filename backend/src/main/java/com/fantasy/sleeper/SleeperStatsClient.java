package com.fantasy.sleeper;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Stats, projections and schedules. These live on Sleeper's api.sleeper.com host,
 * which is not part of the documented public API and may change without notice;
 * callers must tolerate failures.
 */
@Component
public class SleeperStatsClient {

    /**
     * Fantasy-relevant football positions; without this filter the response includes
     * every IDP (~2 MB more). Basketball responses are small enough unfiltered.
     */
    private static final List<String> NFL_POSITIONS = List.of("QB", "RB", "WR", "TE", "K", "DEF");

    private static final ParameterizedTypeReference<List<SleeperWeeklyEntry>> ENTRIES =
            new ParameterizedTypeReference<>() {};

    /** Basketball schedules nest each side's team: "home": {"team": "DET"}. */
    private record NbaGame(
            @JsonProperty("game_id") String gameId,
            @JsonProperty("week") int week,
            @JsonProperty("home") Side home,
            @JsonProperty("away") Side away,
            @JsonProperty("status") String status,
            @JsonProperty("date") String date) {

        private record Side(@JsonProperty("team") String team) {
        }

        SleeperGame toGame() {
            return new SleeperGame(gameId, week, home != null ? home.team() : null, away != null ? away.team() : null,
                    status, date);
        }
    }

    private final RestClient restClient;

    public SleeperStatsClient(RestClient.Builder builder, @Value("${sleeper.stats-base-url}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    /** Football: one line per player. Basketball: one line per player per game. */
    public List<SleeperWeeklyEntry> getProjections(Sport sport, String season, int week) {
        return getWeekly("projections", sport, season, week);
    }

    public List<SleeperWeeklyEntry> getStats(Sport sport, String season, int week) {
        return getWeekly("stats", sport, season, week);
    }

    public List<SleeperGame> getSchedule(Sport sport, String season) {
        if (sport == Sport.NBA) {
            List<NbaGame> games = restClient.get()
                    .uri("/schedule/nba/regular/{season}", season)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<NbaGame>>() {});
            return games != null ? games.stream().map(NbaGame::toGame).toList() : List.of();
        }
        List<SleeperGame> games = restClient.get()
                .uri("/schedule/nfl/regular/{season}", season)
                .retrieve()
                .body(new ParameterizedTypeReference<List<SleeperGame>>() {});
        return games != null ? games : List.of();
    }

    private List<SleeperWeeklyEntry> getWeekly(String kind, Sport sport, String season, int week) {
        List<SleeperWeeklyEntry> entries = restClient.get()
                .uri(uri -> {
                    uri.path("/{kind}/{sport}/{season}/{week}").queryParam("season_type", "regular");
                    if (sport == Sport.NFL) {
                        uri.queryParam("position[]", NFL_POSITIONS.toArray());
                    }
                    return uri.build(kind, sport.code(), season, week);
                })
                .retrieve()
                .body(ENTRIES);
        return entries != null ? entries : List.of();
    }
}
