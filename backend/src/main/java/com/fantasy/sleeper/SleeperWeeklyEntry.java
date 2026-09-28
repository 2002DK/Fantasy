package com.fantasy.sleeper;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One player's stat line for a week: actual stats or a projection, depending on the
 * endpoint. Stat keys match league scoring keys (pass_yd, rec, pts_allow_14_20...),
 * so points are the dot product of the two maps. Team defense rows also carry
 * fan_pts_allow_&lt;pos&gt;: fantasy points the defense allowed to each position.
 */
public record SleeperWeeklyEntry(
        @JsonProperty("player_id") String playerId,
        @JsonProperty("team") String team,
        @JsonProperty("opponent") String opponent,
        @JsonProperty("week") int week,
        @JsonProperty("stats") Map<String, Object> stats) {

    public double stat(String key) {
        return stats != null && stats.get(key) instanceof Number n ? n.doubleValue() : 0;
    }

    public boolean hasStat(String key) {
        return stats != null && stats.get(key) instanceof Number;
    }

    /** Sleeper lists rostered players who did not play, with gp absent or 0. */
    public boolean played() {
        return stat("gp") > 0;
    }
}
