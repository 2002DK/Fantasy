package com.fantasy.sleeper;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One entry from /players/nfl. Team defenses (ID = team code, e.g. "LAR") have no
 * full_name, only first_name "Los Angeles" and last_name "Rams". Free agents have a null team.
 */
public record SleeperPlayer(
        @JsonProperty("player_id") String playerId,
        @JsonProperty("full_name") String fullName,
        @JsonProperty("first_name") String firstName,
        @JsonProperty("last_name") String lastName,
        @JsonProperty("position") String position,
        @JsonProperty("fantasy_positions") List<String> fantasyPositions,
        @JsonProperty("team") String team,
        @JsonProperty("injury_status") String injuryStatus,
        @JsonProperty("active") boolean active,
        @JsonProperty("age") Integer age,
        @JsonProperty("years_exp") Integer yearsExp,
        @JsonProperty("search_rank") Integer searchRank) {

    public String displayName() {
        if (fullName != null) {
            return fullName;
        }
        String name = ((firstName != null ? firstName : "") + " " + (lastName != null ? lastName : "")).trim();
        return name.isEmpty() ? null : name;
    }
}
