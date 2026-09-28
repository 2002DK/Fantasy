package com.fantasy.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;

import com.fantasy.league.LeagueService;
import com.fantasy.league.UserLeaguesResponse;
import com.fantasy.league.UserNotFoundException;

@WebMvcTest(LeagueController.class)
class LeagueControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private LeagueService leagueService;

    @Test
    void returnsLeaguesForUser() throws Exception {
        given(leagueService.findLeagues("dani", null)).willReturn(new UserLeaguesResponse(
                new UserLeaguesResponse.User("123", "dani", "Dani", null),
                "2026",
                List.of(new UserLeaguesResponse.League("L1", "Dynasty", "in_season", 12, null))));

        mvc.perform(get("/api/users/dani/leagues"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.season").value("2026"))
                .andExpect(jsonPath("$.user.displayName").value("Dani"))
                .andExpect(jsonPath("$.leagues[0].leagueId").value("L1"))
                .andExpect(jsonPath("$.leagues[0].totalRosters").value(12));
    }

    @Test
    void unknownUserIs404() throws Exception {
        given(leagueService.findLeagues(any(), any())).willThrow(new UserNotFoundException("ghost"));

        mvc.perform(get("/api/users/ghost/leagues"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No Sleeper user found with username 'ghost'"));
    }

    @Test
    void invalidSeasonIs400() throws Exception {
        mvc.perform(get("/api/users/dani/leagues").param("season", "twenty"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("season must be a four-digit year"));
    }

    @Test
    void sleeperOutageIs502() throws Exception {
        given(leagueService.findLeagues(any(), any())).willThrow(new ResourceAccessException("timeout"));

        mvc.perform(get("/api/users/dani/leagues"))
                .andExpect(status().isBadGateway());
    }
}
