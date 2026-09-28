package com.fantasy.player;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;

import com.fantasy.sleeper.SleeperClient;
import com.fantasy.sleeper.SleeperPlayer;

@DataJpaTest
class PlayerSyncServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");

    @Autowired
    private PlayerRepository playerRepository;

    @Autowired
    private DataSyncRepository dataSyncRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final SleeperClient sleeperClient = mock(SleeperClient.class);

    private PlayerSyncService service;

    @BeforeEach
    void setUp() {
        service = new PlayerSyncService(sleeperClient, playerRepository, dataSyncRepository,
                new TransactionTemplate(transactionManager), Clock.fixed(NOW, ZoneOffset.UTC),
                true, Duration.ofHours(24));
    }

    @Test
    void firstSyncStoresPlayersAndTimestamp() {
        given(sleeperClient.getAllPlayers()).willReturn(Map.of(
                "4866", player("Saquon Barkley", null, null, "RB", List.of("RB"), "PHI"),
                "LAR", player(null, "Los Angeles", "Rams", "DEF", List.of("DEF"), "LAR")));

        service.syncIfStale();

        assertThat(playerRepository.findById("4866")).get()
                .satisfies(p -> {
                    assertThat(p.getFullName()).isEqualTo("Saquon Barkley");
                    assertThat(p.getFantasyPositions()).isEqualTo("RB");
                    assertThat(p.getTeam()).isEqualTo("PHI");
                });
        assertThat(playerRepository.findById("LAR")).get()
                .extracting(Player::getFullName).isEqualTo("Los Angeles Rams");
        assertThat(dataSyncRepository.findById(PlayerSyncService.SYNC_NAME)).get()
                .satisfies(s -> {
                    assertThat(s.getSyncedAt()).isEqualTo(NOW);
                    assertThat(s.getRecordCount()).isEqualTo(2);
                });
    }

    @Test
    void skipsDownloadWhileDataIsFresh() {
        dataSyncRepository.save(new DataSync(PlayerSyncService.SYNC_NAME, NOW.minus(Duration.ofHours(23)), 1));

        service.syncIfStale();

        verify(sleeperClient, never()).getAllPlayers();
    }

    @Test
    void staleDataIsReplacedWholesale() {
        givenExistingSync(NOW.minus(Duration.ofHours(25)), "old-player");
        given(sleeperClient.getAllPlayers()).willReturn(Map.of(
                "4866", player("Saquon Barkley", null, null, "RB", List.of("RB"), "PHI")));

        service.syncIfStale();

        assertThat(playerRepository.findAll()).extracting(Player::getId).containsExactly("4866");
    }

    @Test
    void failedDownloadKeepsExistingData() {
        givenExistingSync(NOW.minus(Duration.ofDays(3)), "old-player");
        given(sleeperClient.getAllPlayers()).willThrow(new ResourceAccessException("timeout"));

        service.syncIfStale();

        assertThat(playerRepository.findAll()).extracting(Player::getId).containsExactly("old-player");
    }

    @Test
    void emptyResponseDoesNotWipeData() {
        givenExistingSync(NOW.minus(Duration.ofDays(3)), "old-player");
        given(sleeperClient.getAllPlayers()).willReturn(Map.of());

        service.syncIfStale();

        assertThat(playerRepository.findAll()).extracting(Player::getId).containsExactly("old-player");
    }

    private void givenExistingSync(Instant syncedAt, String playerId) {
        playerRepository.save(Player.from(playerId, player("Old Player", null, null, "WR", List.of("WR"), null)));
        dataSyncRepository.save(new DataSync(PlayerSyncService.SYNC_NAME, syncedAt, 1));
    }

    private static SleeperPlayer player(String fullName, String firstName, String lastName, String position,
            List<String> fantasyPositions, String team) {
        return new SleeperPlayer(null, fullName, firstName, lastName, position, fantasyPositions, team,
                null, true, null, null, null);
    }
}
