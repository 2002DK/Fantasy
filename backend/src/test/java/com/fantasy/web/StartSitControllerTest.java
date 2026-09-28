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

import com.fantasy.league.InvalidRequestException;
import com.fantasy.startsit.StartSitResponse;
import com.fantasy.startsit.StartSitResponse.Confidence;
import com.fantasy.startsit.StartSitResponse.Recommendation;
import com.fantasy.startsit.StartSitService;

@WebMvcTest(StartSitController.class)
class StartSitControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private StartSitService startSitService;

    @Test
    void returnsComparisonForTeamDefenseIds() throws Exception {
        given(startSitService.compare("111", "LAR", "SEA", 3)).willReturn(new StartSitResponse(
                "2026", 3, new Recommendation("LAR", Confidence.LEAN, 8.2), List.of(), List.of("reason"), List.of()));

        mvc.perform(get("/api/leagues/111/start-sit").param("playerA", "LAR").param("playerB", "SEA").param("week", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendation.playerId").value("LAR"))
                .andExpect(jsonPath("$.recommendation.confidence").value("LEAN"));
    }

    @Test
    void weekOutsideRegularSeasonIs400() throws Exception {
        mvc.perform(get("/api/leagues/111/start-sit").param("playerA", "1").param("playerB", "2").param("week", "19"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("week must be between 1 and 18"));
    }

    @Test
    void missingPlayerIs400() throws Exception {
        mvc.perform(get("/api/leagues/111/start-sit").param("playerA", "1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidRequestIs400WithMessage() throws Exception {
        given(startSitService.compare("111", "1", "1", null))
                .willThrow(new InvalidRequestException("Pick two different players to compare"));

        mvc.perform(get("/api/leagues/111/start-sit").param("playerA", "1").param("playerB", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Pick two different players to compare"));
    }
}
