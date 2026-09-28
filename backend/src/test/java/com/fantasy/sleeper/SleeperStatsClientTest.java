package com.fantasy.sleeper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.test.autoconfigure.RestClientTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

@RestClientTest(SleeperStatsClient.class)
class SleeperStatsClientTest {

    private static final String POSITIONS =
            "position%5B%5D=QB&position%5B%5D=RB&position%5B%5D=WR&position%5B%5D=TE&position%5B%5D=K&position%5B%5D=DEF";

    @Autowired
    private SleeperStatsClient client;

    @Autowired
    private MockRestServiceServer server;

    @Test
    void getStatsFiltersToFantasyPositionsAndReadsStatLines() {
        server.expect(requestTo("https://api.sleeper.com/stats/nfl/2026/2?season_type=regular&" + POSITIONS))
                .andRespond(withSuccess("""
                        [{"player_id":"6770","team":"CIN","opponent":"HOU","week":2,"category":"stat",
                          "stats":{"gp":1.0,"pass_yd":207,"pass_td":2,"pts_ppr":16.18},
                          "player":{"first_name":"Joe","position":"QB"}},
                         {"player_id":"12356","team":"DET","opponent":"BUF","week":2,"stats":{"gms_active":1.0}}]
                        """, MediaType.APPLICATION_JSON));

        List<SleeperWeeklyEntry> entries = client.getStats("2026", 2);

        assertThat(entries).hasSize(2);
        SleeperWeeklyEntry burrow = entries.getFirst();
        assertThat(burrow.opponent()).isEqualTo("HOU");
        assertThat(burrow.stat("pass_yd")).isEqualTo(207);
        assertThat(burrow.played()).isTrue();
        assertThat(entries.get(1).played()).isFalse();
        assertThat(entries.get(1).stat("pass_yd")).isZero();
    }

    @Test
    void getProjectionsUsesProjectionsPath() {
        server.expect(requestTo("https://api.sleeper.com/projections/nfl/2026/3?season_type=regular&" + POSITIONS))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(client.getProjections("2026", 3)).isEmpty();
    }

    @Test
    void getScheduleReadsGames() {
        server.expect(requestTo("https://api.sleeper.com/schedule/nfl/regular/2026"))
                .andRespond(withSuccess("""
                        [{"status":"pre_game","date":"2026-09-28","home":"CHI","week":3,"game_id":"202610306","away":"PHI"},
                         {"status":"canceled","date":"2026-09-28","home":"NYG","week":3,"game_id":"x","away":"DAL"}]
                        """, MediaType.APPLICATION_JSON));

        List<SleeperGame> games = client.getSchedule("2026");

        assertThat(games.getFirst()).isEqualTo(new SleeperGame("202610306", 3, "CHI", "PHI", "pre_game"));
        assertThat(games.get(1).isCanceled()).isTrue();
    }
}
