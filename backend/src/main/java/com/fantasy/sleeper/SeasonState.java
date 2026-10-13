package com.fantasy.sleeper;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SeasonState(
        @JsonProperty("season") String season,
        @JsonProperty("week") int week,
        @JsonProperty("season_type") String seasonType) {
}
