package com.fantasy.player;

/**
 * A player as shown on a roster. {@code name} and the rest are null when the ID is
 * not in the local cache yet (e.g. before the first sync completes).
 */
public record PlayerSummary(String playerId, String name, String position, String team, String injuryStatus) {

    static PlayerSummary from(Player player) {
        return new PlayerSummary(player.getId(), player.getFullName(), player.getPosition(), player.getTeam(),
                player.getInjuryStatus());
    }

    static PlayerSummary unknown(String playerId) {
        return new PlayerSummary(playerId, null, null, null, null);
    }
}
