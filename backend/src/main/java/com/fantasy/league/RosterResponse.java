package com.fantasy.league;

import java.util.List;

/**
 * A user's team in one league. Players are Sleeper player IDs for now; they are
 * resolved to names and positions once the player database is cached.
 */
public record RosterResponse(
        String leagueId,
        String leagueName,
        int rosterId,
        Owner owner,
        TeamRecord record,
        List<Starter> starters,
        List<String> bench,
        List<String> reserve,
        List<String> taxi) {

    public record Owner(String userId, String displayName, String teamName, String avatarUrl) {
    }

    public record TeamRecord(int wins, int losses, int ties, double pointsFor, double pointsAgainst) {
    }

    /** A starting slot such as QB or FLEX. {@code playerId} is null when the slot is empty. */
    public record Starter(String slot, String playerId) {
    }
}
