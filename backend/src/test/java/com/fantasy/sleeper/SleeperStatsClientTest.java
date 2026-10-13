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

        List<SleeperWeeklyEntry> entries = client.getStats(Sport.NFL, "2026", 2);

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

        assertThat(client.getProjections(Sport.NFL, "2026", 3)).isEmpty();
    }

    @Test
    void getScheduleReadsGames() {
        server.expect(requestTo("https://api.sleeper.com/schedule/nfl/regular/2026"))
                .andRespond(withSuccess("""
                        [{"status":"pre_game","date":"2026-09-28","home":"CHI","week":3,"game_id":"202610306","away":"PHI"},
                         {"status":"canceled","date":"2026-09-28","home":"NYG","week":3,"game_id":"x","away":"DAL"}]
                        """, MediaType.APPLICATION_JSON));

        List<SleeperGame> games = client.getSchedule(Sport.NFL, "2026");

        assertThat(games.getFirst()).isEqualTo(new SleeperGame("202610306", 3, "CHI", "PHI", "pre_game", "2026-09-28"));
        assertThat(games.get(1).isCanceled()).isTrue();
    }

    @Test
    void basketballProjectionsAreUnfilteredAndOnePerGame() {
        server.expect(requestTo("https://api.sleeper.com/projections/nba/2026/1?season_type=regular"))
                .andRespond(withSuccess("""
                        [{"player_id":"1362","team":"PHI","opponent":"NYK","week":1,"date":"2026-10-20",
                          "game_id":"g1","stats":{"gp":1.0,"pts":17.53,"reb":5.28}},
                         {"player_id":"1362","team":"PHI","opponent":"CLE","week":1,"date":"2026-10-22",
                          "game_id":"g2","stats":{"gp":1.0,"pts":19.03}}]
                        """, MediaType.APPLICATION_JSON));

        List<SleeperWeeklyEntry> entries = client.getProjections(Sport.NBA, "2026", 1);

        assertThat(entries).extracting(SleeperWeeklyEntry::gameId).containsExactly("g1", "g2");
        assertThat(entries.getFirst().date()).isEqualTo("2026-10-20");
        assertThat(entries.getFirst().stat("pts")).isEqualTo(17.53);
    }

    @Test
    void basketballBoxScoresWithoutGamesPlayedCountPlayingTime() {
        server.expect(requestTo("https://api.sleeper.com/stats/nba/2025/5?season_type=regular"))
                .andRespond(withSuccess("""
                        [{"player_id":"1074","team":"PHI","opponent":"LAC","week":5,"date":"2025-11-17",
                          "game_id":"g9","stats":{"pts":9.0,"reb":7.0,"sp":1266.0}},
                         {"player_id":"1054","team":"DAL","opponent":"MIN","week":5,"date":"2025-11-17",
                          "game_id":"g8","stats":{}}]
                        """, MediaType.APPLICATION_JSON));

        List<SleeperWeeklyEntry> entries = client.getStats(Sport.NBA, "2025", 5);

        assertThat(entries.getFirst().played()).isTrue();
        assertThat(entries.get(1).played()).isFalse();
    }

    @Test
    void basketballScheduleReadsNestedTeams() {
        server.expect(requestTo("https://api.sleeper.com/schedule/nba/regular/2026"))
                .andRespond(withSuccess("""
                        [{"status":"pre_game","date":"2026-10-20","home":{"team":"DET"},"week":1,
                          "game_id":"1392938570779463680","away":{"team":"BOS"}}]
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.getSchedule(Sport.NBA, "2026")).containsExactly(
                new SleeperGame("1392938570779463680", 1, "DET", "BOS", "pre_game", "2026-10-20"));
    }
}
