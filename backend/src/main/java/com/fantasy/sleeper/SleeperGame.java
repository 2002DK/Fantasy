package com.fantasy.sleeper;

import com.fasterxml.jackson.annotation.JsonProperty;

/** A scheduled game. Status is pre_game, in_game, complete or canceled. */
public record SleeperGame(
        @JsonProperty("game_id") String gameId,
        @JsonProperty("week") int week,
        @JsonProperty("home") String home,
        @JsonProperty("away") String away,
        @JsonProperty("status") String status,
        @JsonProperty("date") String date) {

    public SleeperGame(String gameId, int week, String home, String away, String status) {
        this(gameId, week, home, away, status, null);
    }

    public boolean isCanceled() {
        return "canceled".equals(status);
    }
}
