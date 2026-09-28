package com.fantasy.sleeper;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SleeperLeague(
        @JsonProperty("league_id") String leagueId,
        @JsonProperty("name") String name,
        @JsonProperty("season") String season,
        @JsonProperty("status") String status,
        @JsonProperty("total_rosters") int totalRosters,
        @JsonProperty("avatar") String avatar,
        /** Slot per roster position, e.g. QB, RB, FLEX, BN. Starters fill the non-BN slots in order. */
        @JsonProperty("roster_positions") List<String> rosterPositions,
        /** Points per unit of each stat, e.g. rec 0.5 (half PPR), pass_td 6, pass_yd 0.04. */
        @JsonProperty("scoring_settings") Map<String, Double> scoringSettings) {
}
