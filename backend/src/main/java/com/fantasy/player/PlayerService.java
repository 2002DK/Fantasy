package com.fantasy.player;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.fantasy.sleeper.Sport;

/**
 * Looks up cached players. Every lookup names the sport, because Sleeper reuses the
 * same player IDs for different people in different sports.
 */
@Service
public class PlayerService {

    private final PlayerRepository playerRepository;

    public PlayerService(PlayerRepository playerRepository) {
        this.playerRepository = playerRepository;
    }

    public Optional<Player> findPlayer(Sport sport, String playerId) {
        return playerRepository.findById(new PlayerKey(sport, playerId));
    }

    public Optional<PlayerSummary> findSummary(Sport sport, String playerId) {
        return findPlayer(sport, playerId).map(PlayerSummary::from);
    }

    /**
     * Resolves player IDs in one query. Every requested ID gets an entry; IDs missing
     * from the cache map to a summary with only the ID set.
     */
    public Map<String, PlayerSummary> findSummaries(Sport sport, Collection<String> playerIds) {
        Map<String, PlayerSummary> found = playerRepository
                .findAllById(playerIds.stream().map(id -> new PlayerKey(sport, id)).toList()).stream()
                .collect(Collectors.toMap(Player::getPlayerId, PlayerSummary::from));
        return playerIds.stream().distinct()
                .collect(Collectors.toMap(Function.identity(),
                        id -> found.getOrDefault(id, PlayerSummary.unknown(id))));
    }
}
