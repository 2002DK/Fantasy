package com.fantasy.player;

import java.util.List;

import com.fantasy.trade.TradeResponse.TradePlayer;

/**
 * One player in the context of a league: games so far and the remaining schedule in
 * the league's scoring, rest-of-season value, and who rosters them.
 */
public record PlayerDetailResponse(
        PlayerSummary player,
        Integer age,
        Integer yearsExp,
        /** Team name of the league team rostering the player; null for a free agent. */
        String rosteredBy,
        boolean onYourTeam,
        /** Null when the season is over. */
        TradePlayer value,
        List<GamePlayed> games,
        List<Upcoming> upcoming,
        List<String> notes) {

    public record GamePlayed(int week, String opponent, double points) {
    }

    /**
     * {@code opponent} reads like "vs DAL" or "@ BUF" and is null on a bye.
     * {@code matchupRank} 1 = the opponent allows the most points to the position (easiest).
     */
    public record Upcoming(int week, String opponent, Double projected, Integer matchupRank, Integer teams) {
    }
}
