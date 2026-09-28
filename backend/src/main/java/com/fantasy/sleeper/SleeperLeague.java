package com.fantasy.sleeper;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SleeperLeague(
        @JsonProperty("league_id") String leagueId,
        @JsonProperty("name") String name,
        @JsonProperty("season") String season,
        @JsonProperty("status") String status,
        @JsonProperty("total_rosters") int totalRosters,
        @JsonProperty("avatar") String avatar) {
}
