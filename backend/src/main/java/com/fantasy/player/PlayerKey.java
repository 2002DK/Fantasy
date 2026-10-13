package com.fantasy.player;

import java.io.Serializable;
import java.util.Objects;

import com.fantasy.sleeper.Sport;

/** A player's identity: Sleeper player IDs are only unique within a sport. */
public class PlayerKey implements Serializable {

    private String sport;
    private String id;

    protected PlayerKey() {
    }

    public PlayerKey(Sport sport, String id) {
        this.sport = sport.code();
        this.id = id;
    }

    public String getSport() {
        return sport;
    }

    public String getId() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PlayerKey other && Objects.equals(sport, other.sport) && Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sport, id);
    }

    @Override
    public String toString() {
        return sport + ":" + id;
    }
}
