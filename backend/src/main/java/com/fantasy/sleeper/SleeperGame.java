package com.fantasy.sleeper;

import com.fasterxml.jackson.annotation.JsonProperty;

/** A scheduled NFL game. Status is pre_game, in_game, complete or canceled. */
public record SleeperGame(
        @JsonProperty("game_id") String gameId,
        @JsonProperty("week") int week,
        @JsonProperty("home") String home,
        @JsonProperty("away") String away,
        @JsonProperty("status") String status) {

    public boolean isCanceled() {
        return "canceled".equals(status);
    }
}
