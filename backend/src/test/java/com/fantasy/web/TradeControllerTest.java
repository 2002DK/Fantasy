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

import com.fantasy.trade.TradeResponse;
import com.fantasy.trade.TradeResponse.Side;
import com.fantasy.trade.TradeResponse.Strength;
import com.fantasy.trade.TradeResponse.Verdict;
import com.fantasy.trade.TradeResponse.Winner;
import com.fantasy.trade.TradeService;

@WebMvcTest(TradeController.class)
class TradeControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private TradeService tradeService;

    @Test
    void splitsCommaSeparatedPlayerLists() throws Exception {
        given(tradeService.analyze("111", List.of("6794"), List.of("5846", "LAR"))).willReturn(new TradeResponse(
                "2026", 3, 17, new Verdict(Winner.YOU, Strength.SLIGHT, 12.5, 14.2),
                new Side(List.of(), 100, 50), new Side(List.of(), 120, 62.5), List.of(), List.of()));

        mvc.perform(get("/api/leagues/111/trade").param("give", "6794").param("get", "5846,LAR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict.winner").value("YOU"))
                .andExpect(jsonPath("$.get.totalValue").value(62.5));
    }

    @Test
    void malformedPlayerIdIs400() throws Exception {
        mvc.perform(get("/api/leagues/111/trade").param("give", "6794").param("get", "abc;drop"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingSideIs400() throws Exception {
        mvc.perform(get("/api/leagues/111/trade").param("give", "6794"))
                .andExpect(status().isBadRequest());
    }
}
