package com.fantasy.player;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

@Service
public class PlayerService {

    private final PlayerRepository playerRepository;

    public PlayerService(PlayerRepository playerRepository) {
        this.playerRepository = playerRepository;
    }

    /**
     * Resolves player IDs in one query. Every requested ID gets an entry; IDs missing
     * from the cache map to a summary with only the ID set.
     */
    public Map<String, PlayerSummary> findSummaries(Collection<String> playerIds) {
        Map<String, PlayerSummary> found = playerRepository.findAllById(playerIds).stream()
                .collect(Collectors.toMap(Player::getId, PlayerSummary::from));
        return playerIds.stream().distinct()
                .collect(Collectors.toMap(Function.identity(),
                        id -> found.getOrDefault(id, PlayerSummary.unknown(id))));
    }
}
