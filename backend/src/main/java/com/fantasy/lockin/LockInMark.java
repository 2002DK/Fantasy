package com.fantasy.lockin;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/** A score the user marked as locked in for one starter and week. */
@Entity
@Table(name = "lock_in_mark")
@IdClass(LockInMark.Key.class)
public class LockInMark {

    /** Composite primary key: one mark per player per user, league and week. */
    public static class Key implements Serializable {

        private String leagueId;
        private String userId;
        private int week;
        private String playerId;

        protected Key() {
        }

        public Key(String leagueId, String userId, int week, String playerId) {
            this.leagueId = leagueId;
            this.userId = userId;
            this.week = week;
            this.playerId = playerId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && week == k.week && leagueId.equals(k.leagueId) && userId.equals(k.userId)
                    && playerId.equals(k.playerId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(leagueId, userId, week, playerId);
        }
    }

    @Id
    private String leagueId;
    @Id
    private String userId;
    @Id
    private int week;
    @Id
    private String playerId;
    private String gameId;
    private String gameDate;
    private String opponent;
    private double points;
    private Instant markedAt;

    protected LockInMark() {
    }

    public LockInMark(String leagueId, String userId, int week, String playerId, String gameId, String gameDate,
            String opponent, double points, Instant markedAt) {
        this.leagueId = leagueId;
        this.userId = userId;
        this.week = week;
        this.playerId = playerId;
        this.gameId = gameId;
        this.gameDate = gameDate;
        this.opponent = opponent;
        this.points = points;
        this.markedAt = markedAt;
    }

    public String getPlayerId() {
        return playerId;
    }

    public String getGameId() {
        return gameId;
    }

    public String getGameDate() {
        return gameDate;
    }

    public String getOpponent() {
        return opponent;
    }

    public double getPoints() {
        return points;
    }
}
