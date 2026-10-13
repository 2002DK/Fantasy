package com.fantasy.sleeper;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One player's stat line: actual stats or a projection, depending on the endpoint.
 * Football has one line per week; basketball has one per game, with its date and
 * game ID. Stat keys match league scoring keys (pass_yd, rec, pts, reb...), so
 * points are the dot product of the two maps. Football team defense rows also carry
 * fan_pts_allow_&lt;pos&gt;: fantasy points the defense allowed to each position.
 */
public record SleeperWeeklyEntry(
        @JsonProperty("player_id") String playerId,
        @JsonProperty("team") String team,
        @JsonProperty("opponent") String opponent,
        @JsonProperty("week") int week,
        @JsonProperty("stats") Map<String, Object> stats,
        @JsonProperty("date") String date,
        @JsonProperty("game_id") String gameId) {

    /** A weekly (football) line, which has no single game date. */
    public SleeperWeeklyEntry(String playerId, String team, String opponent, int week, Map<String, Object> stats) {
        this(playerId, team, opponent, week, stats, null, null);
    }

    public double stat(String key) {
        return stats != null && stats.get(key) instanceof Number n ? n.doubleValue() : 0;
    }

    public boolean hasStat(String key) {
        return stats != null && stats.get(key) instanceof Number;
    }

    /**
     * Sleeper lists rostered players who did not play, with gp absent or 0. Basketball
     * box scores have no gp at all, so there playing time (seconds, sp) decides.
     */
    public boolean played() {
        return stat("gp") > 0 || stat("sp") > 0;
    }
}
