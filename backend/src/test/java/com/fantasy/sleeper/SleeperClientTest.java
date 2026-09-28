package com.fantasy.sleeper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.test.autoconfigure.RestClientTest;
import org.springframework.http.HttpStatus;
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
                .isEqualTo(List.of(new SleeperLeague("L1", "Dynasty", "2026", "in_season", 12, null, null, null)));
    }

    @Test
    void getLeagueReadsRosterPositions() {
        server.expect(requestTo(BASE + "/league/L1"))
                .andRespond(withSuccess("""
                        {"league_id":"L1","name":"Dynasty","season":"2026","status":"in_season","total_rosters":12,
                         "avatar":null,"roster_positions":["QB","FLEX","BN"],"scoring_settings":{"rec":1.0}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.getLeague("L1")).map(SleeperLeague::rosterPositions)
                .contains(List.of("QB", "FLEX", "BN"));
    }

    @Test
    void getLeagueReturnsEmptyOn404() {
        server.expect(requestTo(BASE + "/league/1"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).body("null").contentType(MediaType.APPLICATION_JSON));

        assertThat(client.getLeague("1")).isEmpty();
    }

    @Test
    void getRostersKeepsNullListsAndSplitPoints() {
        server.expect(requestTo(BASE + "/league/L1/rosters"))
                .andRespond(withSuccess("""
                        [{"roster_id":1,"owner_id":"123","co_owners":null,"players":["4881","CLE"],
                          "starters":["4881","CLE"],"reserve":null,"taxi":null,
                          "settings":{"wins":7,"losses":6,"ties":0,"fpts":1776,"fpts_decimal":6,
                                      "fpts_against":1695,"fpts_against_decimal":36,"waiver_position":4}}]
                        """, MediaType.APPLICATION_JSON));

        SleeperRoster roster = client.getRosters("L1").getFirst();

        assertThat(roster.players()).containsExactly("4881", "CLE");
        assertThat(roster.reserve()).isNull();
        assertThat(roster.settings()).isEqualTo(new SleeperRoster.Settings(7, 6, 0, 1776, 6, 1695, 36));
    }

    @Test
    void getRostersToleratesPreDraftRosterWithMissingFields() {
        // Real shape of a pre-draft league: no fpts_against, unclaimed teams have a null owner
        server.expect(requestTo(BASE + "/league/L1/rosters"))
                .andRespond(withSuccess("""
                        [{"roster_id":1,"owner_id":"123","co_owners":null,"players":[],"reserve":[],"taxi":[],
                          "starters":["0","0"],
                          "settings":{"fpts":0,"fpts_decimal":0,"losses":0,"ties":0,"wins":0,"waiver_position":10}},
                         {"roster_id":2,"owner_id":null,"co_owners":null,"players":[],"starters":["0","0"],
                          "settings":{"wins":0}}]
                        """, MediaType.APPLICATION_JSON));

        List<SleeperRoster> rosters = client.getRosters("L1");

        assertThat(rosters).hasSize(2);
        assertThat(rosters.getFirst().settings()).isEqualTo(new SleeperRoster.Settings(0, 0, 0, 0, 0, 0, 0));
        assertThat(rosters.get(1).ownerId()).isNull();
        assertThat(rosters.get(1).isOwnedBy("123")).isFalse();
    }

    @Test
    void getAllPlayersReadsMapAndNamesDefenses() {
        server.expect(requestTo(BASE + "/players/nfl"))
                .andRespond(withSuccess("""
                        {"4866":{"player_id":"4866","full_name":"Saquon Barkley","first_name":"Saquon","last_name":"Barkley",
                                 "position":"RB","fantasy_positions":["RB"],"team":"PHI","injury_status":null,"active":true,
                                 "age":29,"years_exp":8,"search_rank":11,"hashtag":"#saquonbarkley"},
                         "LAR":{"player_id":"LAR","first_name":"Los Angeles","last_name":"Rams","position":"DEF",
                                "fantasy_positions":["DEF"],"team":"LAR","active":true}}
                        """, MediaType.APPLICATION_JSON));

        Map<String, SleeperPlayer> players = client.getAllPlayers();

        assertThat(players).containsOnlyKeys("4866", "LAR");
        assertThat(players.get("4866").displayName()).isEqualTo("Saquon Barkley");
        assertThat(players.get("4866").searchRank()).isEqualTo(11);
        assertThat(players.get("LAR").displayName()).isEqualTo("Los Angeles Rams");
        assertThat(players.get("LAR").age()).isNull();
    }

    @Test
    void getLeagueUsersReadsTeamNameFromMetadata() {
        server.expect(requestTo(BASE + "/league/L1/users"))
                .andRespond(withSuccess("""
                        [{"user_id":"123","display_name":"Dani","avatar":"abc","metadata":{"team_name":"Giant Dolphins","allow_pn":"on"}},
                         {"user_id":"456","display_name":"Sunny","avatar":null,"metadata":{}}]
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.getLeagueUsers("L1"))
                .extracting(SleeperLeagueUser::teamName)
                .containsExactly("Giant Dolphins", null);
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
