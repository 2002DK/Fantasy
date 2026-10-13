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
import com.fantasy.sleeper.Sport;

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
    void firstSyncStoresEachSportUnderItsOwnKey() {
        // Sleeper reuses IDs across sports: "1000" is a different person in each
        given(sleeperClient.getAllPlayers(Sport.NFL)).willReturn(Map.of(
                "1000", player("Saquon Barkley", null, null, "RB", List.of("RB"), "PHI"),
                "LAR", player(null, "Los Angeles", "Rams", "DEF", List.of("DEF"), "LAR")));
        given(sleeperClient.getAllPlayers(Sport.NBA)).willReturn(Map.of(
                "1000", player("Nikola Jokić", null, null, "C", List.of("C", "PF"), "DEN")));

        service.syncIfStale();

        assertThat(playerRepository.findById(new PlayerKey(Sport.NFL, "1000"))).get()
                .satisfies(p -> {
                    assertThat(p.getFullName()).isEqualTo("Saquon Barkley");
                    assertThat(p.getFantasyPositions()).isEqualTo("RB");
                    assertThat(p.getTeam()).isEqualTo("PHI");
                });
        assertThat(playerRepository.findById(new PlayerKey(Sport.NBA, "1000"))).get()
                .satisfies(p -> {
                    assertThat(p.getFullName()).isEqualTo("Nikola Jokić");
                    assertThat(p.getFantasyPositions()).isEqualTo("C,PF");
                });
        assertThat(playerRepository.findById(new PlayerKey(Sport.NFL, "LAR"))).get()
                .extracting(Player::getFullName).isEqualTo("Los Angeles Rams");
        assertThat(dataSyncRepository.findById(PlayerSyncService.syncName(Sport.NFL))).get()
                .satisfies(sync -> {
                    assertThat(sync.getSyncedAt()).isEqualTo(NOW);
                    assertThat(sync.getRecordCount()).isEqualTo(2);
                });
        assertThat(dataSyncRepository.findById(PlayerSyncService.syncName(Sport.NBA))).get()
                .extracting(DataSync::getRecordCount).isEqualTo(1);
    }

    @Test
    void onlyStaleSportsAreDownloaded() {
        dataSyncRepository.save(new DataSync(PlayerSyncService.syncName(Sport.NFL), NOW.minus(Duration.ofHours(23)), 1));
        given(sleeperClient.getAllPlayers(Sport.NBA)).willReturn(Map.of(
                "1658", player("Nikola Jokić", null, null, "C", List.of("C"), "DEN")));

        service.syncIfStale();

        verify(sleeperClient, never()).getAllPlayers(Sport.NFL);
        assertThat(playerRepository.countBySport("nba")).isEqualTo(1);
    }

    @Test
    void staleSportIsReplacedWithoutTouchingTheOther() {
        givenExistingSync(Sport.NFL, NOW.minus(Duration.ofHours(25)), "old-player");
        givenExistingSync(Sport.NBA, NOW.minus(Duration.ofHours(1)), "nba-player");
        given(sleeperClient.getAllPlayers(Sport.NFL)).willReturn(Map.of(
                "4866", player("Saquon Barkley", null, null, "RB", List.of("RB"), "PHI")));

        service.syncIfStale();

        assertThat(playerIds(Sport.NFL)).containsExactly("4866");
        assertThat(playerIds(Sport.NBA)).containsExactly("nba-player");
    }

    @Test
    void failedDownloadKeepsThatSportsDataAndStillSyncsTheOther() {
        givenExistingSync(Sport.NFL, NOW.minus(Duration.ofDays(3)), "old-player");
        given(sleeperClient.getAllPlayers(Sport.NFL)).willThrow(new ResourceAccessException("timeout"));
        given(sleeperClient.getAllPlayers(Sport.NBA)).willReturn(Map.of(
                "1658", player("Nikola Jokić", null, null, "C", List.of("C"), "DEN")));

        service.syncIfStale();

        assertThat(playerIds(Sport.NFL)).containsExactly("old-player");
        assertThat(playerIds(Sport.NBA)).containsExactly("1658");
    }

    @Test
    void emptyResponseDoesNotWipeData() {
        givenExistingSync(Sport.NFL, NOW.minus(Duration.ofDays(3)), "old-player");
        given(sleeperClient.getAllPlayers(Sport.NFL)).willReturn(Map.of());
        given(sleeperClient.getAllPlayers(Sport.NBA)).willReturn(Map.of());

        service.syncIfStale();

        assertThat(playerIds(Sport.NFL)).containsExactly("old-player");
    }

    private List<String> playerIds(Sport sport) {
        return playerRepository.findAll().stream().filter(p -> p.getSport() == sport).map(Player::getPlayerId).toList();
    }

    private void givenExistingSync(Sport sport, Instant syncedAt, String playerId) {
        playerRepository.save(Player.from(sport, playerId, player("Old Player", null, null, "WR", List.of("WR"), null)));
        dataSyncRepository.save(new DataSync(PlayerSyncService.syncName(sport), syncedAt, 1));
    }

    private static SleeperPlayer player(String fullName, String firstName, String lastName, String position,
            List<String> fantasyPositions, String team) {
        return new SleeperPlayer(null, fullName, firstName, lastName, position, fantasyPositions, team,
                null, true, null, null, null);
    }
}
