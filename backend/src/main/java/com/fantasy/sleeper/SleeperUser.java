package com.fantasy.sleeper;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SleeperUser(
        @JsonProperty("user_id") String userId,
        @JsonProperty("username") String username,
        @JsonProperty("display_name") String displayName,
        @JsonProperty("avatar") String avatar) {
}
