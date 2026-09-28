package com.fantasy.sleeper;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SleeperLeague(
        @JsonProperty("league_id") String leagueId,
        @JsonProperty("name") String name,
        @JsonProperty("season") String season,
        @JsonProperty("status") String status,
        @JsonProperty("total_rosters") int totalRosters,
        @JsonProperty("avatar") String avatar,
        /** Slot per roster position, e.g. QB, RB, FLEX, BN. Starters fill the non-BN slots in order. */
        @JsonProperty("roster_positions") List<String> rosterPositions) {
}
