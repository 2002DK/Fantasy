package com.fantasy.league;

import java.util.List;

import com.fantasy.player.PlayerSummary;

/** A user's team in one league, with players resolved from the local player cache. */
public record RosterResponse(
        String leagueId,
        String leagueName,
        int rosterId,
        Owner owner,
        TeamRecord record,
        List<Starter> starters,
        List<PlayerSummary> bench,
        List<PlayerSummary> reserve,
        List<PlayerSummary> taxi) {

    public record Owner(String userId, String displayName, String teamName, String avatarUrl) {
    }

    public record TeamRecord(int wins, int losses, int ties, double pointsFor, double pointsAgainst) {
    }

    /** A starting slot such as QB or FLEX. {@code player} is null when the slot is empty. */
    public record Starter(String slot, PlayerSummary player) {
    }
}
