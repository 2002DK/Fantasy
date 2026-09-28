package com.fantasy.sleeper;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One team in a league. Player lists hold Sleeper player IDs (team defenses use the
 * team code, e.g. "CLE"). Sleeper sends {@code null} rather than an empty list for
 * unused reserve/taxi, and "0" for an empty starting slot.
 */
public record SleeperRoster(
        @JsonProperty("roster_id") int rosterId,
        @JsonProperty("owner_id") String ownerId,
        @JsonProperty("co_owners") List<String> coOwners,
        @JsonProperty("players") List<String> players,
        @JsonProperty("starters") List<String> starters,
        @JsonProperty("reserve") List<String> reserve,
        @JsonProperty("taxi") List<String> taxi,
        @JsonProperty("settings") Settings settings) {

    /** Points are split into a whole part and hundredths, e.g. fpts 1776 + fpts_decimal 6 = 1776.06. */
    public record Settings(
            @JsonProperty("wins") int wins,
            @JsonProperty("losses") int losses,
            @JsonProperty("ties") int ties,
            @JsonProperty("fpts") int fpts,
            @JsonProperty("fpts_decimal") int fptsDecimal,
            @JsonProperty("fpts_against") int fptsAgainst,
            @JsonProperty("fpts_against_decimal") int fptsAgainstDecimal) {
    }

    public boolean isOwnedBy(String userId) {
        return userId.equals(ownerId) || (coOwners != null && coOwners.contains(userId));
    }
}
