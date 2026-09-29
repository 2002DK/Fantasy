package com.fantasy.web;

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

import com.fantasy.league.NotFoundException;
import com.fantasy.lineup.LineupResponse;
import com.fantasy.lineup.LineupService;
import com.fantasy.matchup.MatchupService;
import com.fantasy.planner.PlannerService;
import com.fantasy.player.PlayerDetailService;
import com.fantasy.standings.StandingsResponse;
import com.fantasy.standings.StandingsService;
import com.fantasy.trade.TradeFinderService;

@WebMvcTest(TeamToolsController.class)
class TeamToolsControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private LineupService lineupService;
    @MockitoBean
    private MatchupService matchupService;
    @MockitoBean
    private StandingsService standingsService;
    @MockitoBean
    private TradeFinderService tradeFinderService;
    @MockitoBean
    private PlannerService plannerService;
    @MockitoBean
    private PlayerDetailService playerDetailService;

    @Test
    void returnsLineup() throws Exception {
        given(lineupService.optimize("111", "222")).willReturn(
                new LineupResponse(4, List.of(), 90, 104.5, 14.5, List.of("Start A instead of B"), List.of()));

        mvc.perform(get("/api/leagues/111/users/222/lineup"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gain").value(14.5))
                .andExpect(jsonPath("$.changes[0]").value("Start A instead of B"));
    }

    @Test
    void standingsUserIdIsOptional() throws Exception {
        given(standingsService.standings("111", null)).willReturn(
                new StandingsResponse(4, 14, 6, null, List.of(), List.of()));

        mvc.perform(get("/api/leagues/111/standings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regularSeasonEnd").value(14));
    }

    @Test
    void rejectsMalformedIds() throws Exception {
        mvc.perform(get("/api/leagues/abc/users/222/matchup")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/leagues/111/users/222x/planner")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/leagues/111/players/abc;drop")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/leagues/111/standings").param("userId", "me")).andExpect(status().isBadRequest());
    }

    @Test
    void userWithoutRosterIs404() throws Exception {
        given(tradeFinderService.findIdeas("111", "222")).willThrow(new NotFoundException("User '222' has no roster"));

        mvc.perform(get("/api/leagues/111/users/222/trade-ideas"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("User '222' has no roster"));
    }
}
