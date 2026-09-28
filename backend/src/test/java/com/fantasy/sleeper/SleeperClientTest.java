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

@RestClientTest(SleeperClient.class)
class SleeperClientTest {

    private static final String BASE = "https://api.sleeper.app/v1";

    @Autowired
    private SleeperClient client;

    @Autowired
    private MockRestServiceServer server;

    @Test
    void getUserMapsSnakeCaseFields() {
        server.expect(requestTo(BASE + "/user/dani"))
                .andRespond(withSuccess("""
                        {"user_id":"123","username":"dani","display_name":"Dani","avatar":"abc","is_bot":false}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.getUser("dani"))
                .contains(new SleeperUser("123", "dani", "Dani", "abc"));
    }

    @Test
    void getUserReturnsEmptyWhenSleeperAnswersNull() {
        server.expect(requestTo(BASE + "/user/nobody"))
                .andRespond(withSuccess("null", MediaType.APPLICATION_JSON));

        assertThat(client.getUser("nobody")).isEmpty();
    }

    @Test
    void getLeaguesMapsList() {
        server.expect(requestTo(BASE + "/user/123/leagues/nfl/2026"))
                .andRespond(withSuccess("""
                        [{"league_id":"L1","name":"Dynasty","season":"2026","status":"in_season","total_rosters":12,"avatar":null}]
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.getLeagues("123", "2026"))
                .isEqualTo(List.of(new SleeperLeague("L1", "Dynasty", "2026", "in_season", 12, null)));
    }

    @Test
    void getNflStateReadsCurrentSeason() {
        server.expect(requestTo(BASE + "/state/nfl"))
                .andRespond(withSuccess("""
                        {"week":3,"season":"2026","season_type":"regular","previous_season":"2025"}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.getNflState()).isEqualTo(new NflState("2026", 3, "regular"));
    }
}
