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
import com.fantasy.sleeper.Sport;

/**
 * Keeps the local player table in step with Sleeper's player lists, one per sport
 * (/players/nfl ~15 MB, /players/nba ~2.5 MB). Sleeper asks that these be fetched at
 * most once a day, so the sync is checked on startup and hourly but only downloads a
 * sport when its stored copy is older than {@code app.players.max-age}. Each sport
 * syncs independently, so one failing leaves the other untouched.
 */
@Service
public class PlayerSyncService {

    /** The data_sync record for a sport, e.g. "players-nba". */
    static String syncName(Sport sport) {
        return "players-" + sport.code();
    }

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

    /** Downloads each sport's players when its cache is missing or too old. Failures keep the existing data. */
    public synchronized void syncIfStale() {
        for (Sport sport : Sport.values()) {
            syncIfStale(sport);
        }
    }

    void syncIfStale(Sport sport) {
        Instant lastSync = dataSyncRepository.findById(syncName(sport)).map(DataSync::getSyncedAt).orElse(null);
        if (lastSync != null && lastSync.plus(maxAge).isAfter(clock.instant())) {
            log.info("{} player data is fresh (synced {}); skipping download", sport.displayName(), lastSync);
            return;
        }
        try {
            int count = sync(sport);
            log.info("Synced {} {} players from Sleeper", count, sport.displayName().toLowerCase());
        } catch (RestClientException e) {
            log.warn("{} player sync failed; keeping existing player data", sport.displayName(), e);
        }
    }

    /** Replaces one sport's stored players with Sleeper's current list in a single transaction. */
    int sync(Sport sport) {
        Map<String, SleeperPlayer> sleeperPlayers = sleeperClient.getAllPlayers(sport);
        if (sleeperPlayers.isEmpty()) {
            throw new RestClientException("Sleeper returned no players");
        }
        List<Player> players = sleeperPlayers.entrySet().stream()
                .map(e -> Player.from(sport, e.getKey(), e.getValue()))
                .toList();
        transactionTemplate.executeWithoutResult(status -> {
            playerRepository.deleteBySport(sport.code());
            playerRepository.saveAll(players);
            dataSyncRepository.save(new DataSync(syncName(sport), clock.instant(), players.size()));
        });
        return players.size();
    }
}
