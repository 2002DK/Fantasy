package com.fantasy.trade;

import java.util.List;

import com.fantasy.trade.TradeResponse.TradePlayer;

/**
 * Trades (one-for-one, two-for-one and one-for-two) that improve both teams' expected
 * starting lineups for the rest of the season, best for the user first.
 */
public record TradeIdeasResponse(int fromWeek, int throughWeek, List<Idea> ideas, List<String> notes) {

    /**
     * Gains are expected starting-lineup points per remaining week; the players carry the
     * trade analyzer's rest-of-season value above replacement.
     */
    public record Idea(int partnerRosterId, String partnerName, List<TradePlayer> give, List<TradePlayer> get,
            double yourGainPerWeek, double theirGainPerWeek, String reason) {
    }
}
