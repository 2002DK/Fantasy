package com.fantasy.lockin;

import java.util.List;

import com.fantasy.player.PlayerSummary;

/**
 * Lock-or-pass advice for each of the user's starters this week. Dates are the games'
 * local (US) dates as Sleeper lists them, e.g. 2026-11-03.
 */
public record LockInAdviceResponse(int week, List<StarterAdvice> starters, List<String> notes) {

    public enum Action {
        /** The user marked this score as locked. */
        LOCKED,
        /** The latest score beats the value of waiting: lock it before the next game. */
        LOCK,
        /** Waiting for the remaining games is worth more than the latest score. */
        PASS,
        /** In a game right now; decide when it ends. */
        PLAYING,
        /** Hasn't played this week yet; the thresholds say what to lock after each game. */
        WAITING,
        /** No games left: the last game counts unless an earlier score was locked. */
        LAST_GAME,
        /** No games this week, or unavailable (injured reserve, not on a team). */
        NO_GAMES
    }

    /**
     * {@code waitValue} is the Lock-In value of the games still to come (null without
     * any). {@code canLock} is true when there is a finished game this week to mark as
     * locked; {@code canUnmark} when the lock is one the user marked (a lock read from
     * Sleeper's score can't be unmarked).
     */
    public record StarterAdvice(String slot, PlayerSummary player, Action action, String message,
            PlayedGame latestGame, Double waitValue, List<UpcomingGame> upcoming, PlayedGame lockedGame,
            boolean canLock, boolean canUnmark) {
    }

    public record PlayedGame(String gameId, String date, String opponent, double points) {
    }

    /** {@code lockAbove} is the score worth locking after this game; null for the last game, which counts. */
    public record UpcomingGame(String date, String opponent, boolean home, double expectedPoints, Double lockAbove) {
    }
}
