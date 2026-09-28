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
import com.fantasy.league.RosterResponse;
import com.fantasy.league.RosterService;

@WebMvcTest(RosterController.class)
class RosterControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private RosterService rosterService;

    @Test
    void returnsRoster() throws Exception {
        given(rosterService.findRoster("111", "222")).willReturn(new RosterResponse(
                "111", "Dynasty", 2,
                new RosterResponse.Owner("222", "Dani", "Giant Dolphins", null),
                new RosterResponse.TeamRecord(7, 6, 0, 1776.06, 1695.36),
                List.of(new RosterResponse.Starter("QB", "4881")),
                List.of("400"), List.of(), List.of()));

        mvc.perform(get("/api/leagues/111/users/222/roster"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owner.teamName").value("Giant Dolphins"))
                .andExpect(jsonPath("$.record.pointsFor").value(1776.06))
                .andExpect(jsonPath("$.starters[0].slot").value("QB"))
                .andExpect(jsonPath("$.bench[0]").value("400"));
    }

    @Test
    void missingRosterIs404() throws Exception {
        given(rosterService.findRoster("111", "222")).willThrow(new NotFoundException("User '222' has no roster"));

        mvc.perform(get("/api/leagues/111/users/222/roster"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("User '222' has no roster"));
    }

    @Test
    void nonNumericIdIs400() throws Exception {
        mvc.perform(get("/api/leagues/abc/users/222/roster"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("leagueId must be a numeric Sleeper ID"));
    }
}
