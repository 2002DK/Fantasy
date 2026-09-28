package com.fantasy.player;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import com.fantasy.sleeper.SleeperPlayer;

@DataJpaTest
@Import(PlayerService.class)
class PlayerServiceTest {

    @Autowired
    private PlayerRepository playerRepository;

    @Autowired
    private PlayerService playerService;

    @Test
    void resolvesKnownIdsAndKeepsUnknownOnes() {
        playerRepository.save(Player.from("4866", new SleeperPlayer(
                "4866", "Saquon Barkley", "Saquon", "Barkley", "RB", List.of("RB"), "PHI",
                "Questionable", true, 29, 8, 11)));

        assertThat(playerService.findSummaries(List.of("4866", "9999")))
                .containsEntry("4866", new PlayerSummary("4866", "Saquon Barkley", "RB", "PHI", "Questionable"))
                .containsEntry("9999", new PlayerSummary("9999", null, null, null, null));
    }
}
