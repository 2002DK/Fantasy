package com.fantasy.player;

import org.springframework.data.domain.Persistable;

import com.fantasy.sleeper.SleeperPlayer;
import com.fantasy.sleeper.Sport;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/** Cached copy of a Sleeper player, trimmed to the fields the app uses; keyed by sport and ID. */
@Entity
@Table(name = "player")
@IdClass(PlayerKey.class)
public class Player implements Persistable<PlayerKey> {

    /** Sleeper's sport code, "nfl" or "nba". */
    @Id
    private String sport;
    @Id
    private String id;
    private String fullName;
    private String position;
    /** Comma-separated, e.g. "RB", "QB,TE" or "PG,SG". Drives flex and multi-position slot eligibility. */
    private String fantasyPositions;
    private String team;
    private String injuryStatus;
    private boolean active;
    private Integer age;
    private Integer yearsExp;
    private Integer searchRank;

    /**
     * The sync inserts into an emptied table, so new rows skip Spring Data's
     * select-before-insert merge check. Loaded rows are marked not-new.
     */
    @Transient
    private boolean isNew = true;

    protected Player() {
    }

    static Player from(Sport sport, String id, SleeperPlayer p) {
        Player player = new Player();
        player.sport = sport.code();
        player.id = id;
        player.fullName = p.displayName();
        player.position = p.position();
        player.fantasyPositions = p.fantasyPositions() != null ? String.join(",", p.fantasyPositions()) : null;
        player.team = p.team();
        player.injuryStatus = p.injuryStatus();
        player.active = p.active();
        player.age = p.age();
        player.yearsExp = p.yearsExp();
        player.searchRank = p.searchRank();
        return player;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        isNew = false;
    }

    @Override
    public PlayerKey getId() {
        return new PlayerKey(Sport.fromCode(sport), id);
    }

    /** The Sleeper player ID, unique within the sport. */
    public String getPlayerId() {
        return id;
    }

    public Sport getSport() {
        return Sport.fromCode(sport);
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public String getFullName() {
        return fullName;
    }

    public String getPosition() {
        return position;
    }

    public String getFantasyPositions() {
        return fantasyPositions;
    }

    public String getTeam() {
        return team;
    }

    public String getInjuryStatus() {
        return injuryStatus;
    }

    public boolean isActive() {
        return active;
    }

    public Integer getAge() {
        return age;
    }

    public Integer getYearsExp() {
        return yearsExp;
    }

    public Integer getSearchRank() {
        return searchRank;
    }
}
