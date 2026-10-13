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
        List<String> notes,
        /** Basketball only: each remaining week's games and Lock-In value; null for football. */
        List<WeekValue> lockInWeeks) {

    public PlayerDetailResponse(PlayerSummary player, Integer age, Integer yearsExp, String rosteredBy,
            boolean onYourTeam, TradePlayer value, List<GamePlayed> games, List<Upcoming> upcoming,
            List<String> notes) {
        this(player, age, yearsExp, rosteredBy, onYourTeam, value, games, upcoming, notes, null);
    }

    /** One game (basketball, with its date) or one week's game (football). */
    public record GamePlayed(int week, String opponent, double points, String date) {

        public GamePlayed(int week, String opponent, double points) {
            this(week, opponent, points, null);
        }
    }

    /**
     * {@code opponent} reads like "vs DAL" or "@ BUF" and is null on a bye.
     * {@code matchupRank} 1 = the opponent allows the most points to the position (easiest).
     * Basketball lists games, with their date, for this week and next.
     */
    public record Upcoming(int week, String opponent, Double projected, Integer matchupRank, Integer teams,
            String date) {

        public Upcoming(int week, String opponent, Double projected, Integer matchupRank, Integer teams) {
            this(week, opponent, projected, matchupRank, teams, null);
        }
    }

    public record WeekValue(int week, int games, double value) {
    }
}
