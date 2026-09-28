package com.fantasy.sleeper;

import com.fasterxml.jackson.annotation.JsonProperty;

/** A member of a league. The team name lives in league-specific metadata and is often unset. */
public record SleeperLeagueUser(
        @JsonProperty("user_id") String userId,
        @JsonProperty("display_name") String displayName,
        @JsonProperty("avatar") String avatar,
        @JsonProperty("metadata") Metadata metadata) {

    public record Metadata(@JsonProperty("team_name") String teamName) {
    }

    public String teamName() {
        return metadata != null ? metadata.teamName() : null;
    }
}
