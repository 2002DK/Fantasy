package com.fantasy.sleeper;

import com.fasterxml.jackson.annotation.JsonProperty;

public record NflState(
        @JsonProperty("season") String season,
        @JsonProperty("week") int week,
        @JsonProperty("season_type") String seasonType) {
}
