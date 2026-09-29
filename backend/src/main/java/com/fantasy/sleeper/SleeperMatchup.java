package com.fantasy.sleeper;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One team's entry in a league week. Teams sharing a {@code matchupId} play each
 * other; Sleeper publishes pairings for every regular-season week in advance.
 * Points are live during the week and 0 before games start.
 */
public record SleeperMatchup(
        @JsonProperty("roster_id") int rosterId,
        /** Null for teams without a game that week (e.g. eliminated in the playoffs). */
        @JsonProperty("matchup_id") Integer matchupId,
        @JsonProperty("points") double points,
        @JsonProperty("starters") List<String> starters,
        @JsonProperty("players_points") Map<String, Double> playersPoints) {
}
