package com.fantasy.player;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlayerRepository extends JpaRepository<Player, PlayerKey> {

    /** Clears one sport's cached players in a single statement before a sync re-inserts them. */
    @Modifying
    @Query("delete from Player p where p.sport = :sport")
    int deleteBySport(@Param("sport") String sport);

    long countBySport(String sport);
}
