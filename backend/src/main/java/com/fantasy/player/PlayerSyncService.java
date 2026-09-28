package com.fantasy.player;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

import com.fantasy.sleeper.SleeperClient;
import com.fantasy.sleeper.SleeperPlayer;

/**
 * Keeps the local player table in step with Sleeper's /players/nfl. Sleeper asks
 * that this ~15 MB response be fetched at most once a day, so the sync is checked
 * on startup and hourly but only downloads when the stored copy is older than
 * {@code app.players.max-age}.
 */
@Service
public class PlayerSyncService {

    static final String SYNC_NAME = "players";

    private static final Logger log = LoggerFactory.getLogger(PlayerSyncService.class);

    private final SleeperClient sleeperClient;
    private final PlayerRepository playerRepository;
    private final DataSyncRepository dataSyncRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final boolean syncEnabled;
    private final Duration maxAge;

    public PlayerSyncService(SleeperClient sleeperClient, PlayerRepository playerRepository,
            DataSyncRepository dataSyncRepository, TransactionTemplate transactionTemplate, Clock clock,
            @Value("${app.players.sync-enabled}") boolean syncEnabled,
            @Value("${app.players.max-age}") Duration maxAge) {
        this.sleeperClient = sleeperClient;
        this.playerRepository = playerRepository;
        this.dataSyncRepository = dataSyncRepository;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
        this.syncEnabled = syncEnabled;
        this.maxAge = maxAge;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartup() {
        if (syncEnabled) {
            syncIfStale();
        }
    }

    @Scheduled(cron = "${app.players.sync-check-cron}")
    public void scheduledSync() {
        if (syncEnabled) {
            syncIfStale();
        }
    }

    /** Downloads players when the cache is missing or too old. Failures keep the existing data. */
    public synchronized void syncIfStale() {
        Instant lastSync = dataSyncRepository.findById(SYNC_NAME).map(DataSync::getSyncedAt).orElse(null);
        if (lastSync != null && lastSync.plus(maxAge).isAfter(clock.instant())) {
            log.info("Player data is fresh (synced {}); skipping download", lastSync);
            return;
        }
        try {
            int count = sync();
            log.info("Synced {} players from Sleeper", count);
        } catch (RestClientException e) {
            log.warn("Player sync failed; keeping existing player data", e);
        }
    }

    /** Replaces every stored player with Sleeper's current list in a single transaction. */
    int sync() {
        Map<String, SleeperPlayer> sleeperPlayers = sleeperClient.getAllPlayers();
        if (sleeperPlayers.isEmpty()) {
            throw new RestClientException("Sleeper returned no players");
        }
        List<Player> players = sleeperPlayers.entrySet().stream()
                .map(e -> Player.from(e.getKey(), e.getValue()))
                .toList();
        transactionTemplate.executeWithoutResult(status -> {
            playerRepository.deleteAllInBatch();
            playerRepository.saveAll(players);
            dataSyncRepository.save(new DataSync(SYNC_NAME, clock.instant(), players.size()));
        });
        return players.size();
    }
}
