package com.fantasy.planner;

import java.util.List;

import com.fantasy.player.PlayerSummary;

/** The user's roster across the remaining weeks: who is available each week and where lineups come up short. */
public record PlannerResponse(List<Integer> weeks, List<Row> players, List<Shortage> shortages, List<String> notes) {

    public record Row(PlayerSummary player, boolean starter, List<Cell> cells) {
    }

    /**
     * {@code status} is "ok", "bye", "out", "doubtful", "questionable" or "played" (this
     * week's game already started). {@code opponent} reads like "vs DAL" or "@ BUF".
     * Basketball sets {@code games} (games that week, or left this week), with
     * {@code projected} the week's Lock-In value and "bye" meaning no games.
     */
    public record Cell(int week, String status, Double projected, String opponent, Integer games) {

        public Cell(int week, String status, Double projected, String opponent) {
            this(week, status, projected, opponent, null);
        }
    }

    /** A week in which the available players can't fill these starting slots. */
    public record Shortage(int week, List<String> unfilledSlots, String message) {
    }
}
