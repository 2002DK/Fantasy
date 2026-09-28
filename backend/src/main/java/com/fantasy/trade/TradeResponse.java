package com.fantasy.trade;

import java.util.List;

import com.fantasy.player.PlayerSummary;

/**
 * A trade evaluated from the requesting user's side: {@code give} is what they
 * send away, {@code get} what they receive. Value is rest-of-season points above a
 * replacement-level player at the same position, in the league's own scoring.
 */
public record TradeResponse(
        String season,
        int fromWeek,
        int throughWeek,
        Verdict verdict,
        Side give,
        Side get,
        List<String> reasons,
        List<String> notes) {

    public enum Winner {
        YOU,
        THEM
    }

    public enum Strength {
        /** Values within 10% of each other. */
        FAIR,
        /** Within 25%. */
        SLIGHT,
        CLEAR
    }

    /** {@code winner} is null when both sides have no value to compare. */
    public record Verdict(Winner winner, Strength strength, double valueDifference, double marginPercent) {
    }

    public record Side(List<TradePlayer> players, double totalPoints, double totalValue) {
    }

    public record TradePlayer(
            PlayerSummary player,
            int remainingGames,
            /** Sum of weekly projections for the remaining games; null when projections are unavailable. */
            Double projectedPoints,
            Double recentAverage,
            int recentGames,
            /** Positive = remaining opponents allow more points than average (easier). Null without data. */
            Double scheduleStrengthPercent,
            /** Blend of projections and recent form over the remaining games. */
            double restOfSeasonPoints,
            double replacementPoints,
            /** Points above replacement; 0 at or below replacement level. */
            double value,
            /** Why the player's value is limited, e.g. "IR" or "No starting slot for K". */
            String note) {
    }
}
