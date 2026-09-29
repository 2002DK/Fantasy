package com.fantasy.lineup;

import java.util.List;

import com.fantasy.player.PlayerSummary;

/**
 * The best starting lineup for a week next to the current one. Expected points use
 * the start/sit model (projections, recent form, matchup, injuries) in the league's
 * scoring.
 */
public record LineupResponse(
        int week,
        List<Slot> slots,
        double currentTotal,
        double optimalTotal,
        double gain,
        /** Plain-English lineup moves, biggest gain first; empty when the lineup is already optimal. */
        List<String> changes,
        List<String> notes) {

    /** {@code scored} is false for slots the optimizer leaves alone (defensive players). */
    public record Slot(String slot, LineupPlayer current, LineupPlayer optimal, boolean scored) {
    }

    /**
     * {@code opponent} reads like "vs DAL" or "@ BUF"; null on a bye. {@code locked} means
     * the player's game has started, so Sleeper no longer lets them move.
     */
    public record LineupPlayer(PlayerSummary player, double expectedPoints, boolean available, String availabilityNote,
            String opponent, boolean locked) {
    }
}
