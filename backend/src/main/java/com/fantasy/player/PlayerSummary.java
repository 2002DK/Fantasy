package com.fantasy.player;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * A player as shown on a roster. {@code name} and the rest are null when the ID is
 * not in the local cache yet (e.g. before the first sync completes).
 * {@code eligiblePositions} are the lineup positions Sleeper allows: one for most
 * football players, often two for basketball players (PG/SG, SF/PF).
 */
public record PlayerSummary(String playerId, String name, String position, String team, String injuryStatus,
        List<String> eligiblePositions) {

    /** A player eligible only at their listed position. */
    public PlayerSummary(String playerId, String name, String position, String team, String injuryStatus) {
        this(playerId, name, position, team, injuryStatus, position != null ? List.of(position) : List.of());
    }

    static PlayerSummary from(Player player) {
        String fantasyPositions = player.getFantasyPositions();
        List<String> eligible = fantasyPositions == null || fantasyPositions.isBlank()
                ? (player.getPosition() != null ? List.of(player.getPosition()) : List.of())
                : Arrays.stream(fantasyPositions.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        return new PlayerSummary(player.getPlayerId(), player.getFullName(), player.getPosition(), player.getTeam(),
                player.getInjuryStatus(), eligible);
    }

    static PlayerSummary unknown(String playerId) {
        return new PlayerSummary(playerId, null, null, null, null);
    }

    /** Whether the player may fill a slot that accepts any of these positions. */
    public boolean fitsAny(Set<String> positions) {
        List<String> eligible = eligiblePositions != null && !eligiblePositions.isEmpty() ? eligiblePositions
                : position != null ? List.of(position) : List.of();
        return eligible.stream().anyMatch(positions::contains);
    }
}
