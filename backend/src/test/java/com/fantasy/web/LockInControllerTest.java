package com.fantasy.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fantasy.league.InvalidRequestException;
import com.fantasy.lockin.LockInAdviceResponse;
import com.fantasy.lockin.LockInAdviceResponse.Action;
import com.fantasy.lockin.LockInAdviceResponse.StarterAdvice;
import com.fantasy.lockin.LockInAdvisorService;
import com.fantasy.player.PlayerSummary;

@WebMvcTest(LockInController.class)
class LockInControllerTest {

    private static final LockInAdviceResponse ADVICE = new LockInAdviceResponse(3, List.of(new StarterAdvice("PG",
            new PlayerSummary("4", "Jokić", "C", "DEN", null), Action.LOCK, "Lock it in", null, 48.1, List.of(),
            null, true)), List.of());

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private LockInAdvisorService advisor;

    @Test
    void returnsAdvice() throws Exception {
        given(advisor.advice("111", "222")).willReturn(ADVICE);

        mvc.perform(get("/api/leagues/111/users/222/lock-in"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.starters[0].action").value("LOCK"))
                .andExpect(jsonPath("$.starters[0].waitValue").value(48.1));
    }

    @Test
    void locksAndUnlocks() throws Exception {
        given(advisor.lock("111", "222", "4")).willReturn(ADVICE);
        given(advisor.unlock("111", "222", "4")).willReturn(ADVICE);

        mvc.perform(put("/api/leagues/111/users/222/lock-in/4")).andExpect(status().isOk());
        mvc.perform(delete("/api/leagues/111/users/222/lock-in/4")).andExpect(status().isOk());

        verify(advisor).lock("111", "222", "4");
        verify(advisor).unlock("111", "222", "4");
    }

    @Test
    void invalidLockIsABadRequestWithTheReason() throws Exception {
        given(advisor.lock("111", "222", "4"))
                .willThrow(new InvalidRequestException("Only your current starters can be locked."));

        mvc.perform(put("/api/leagues/111/users/222/lock-in/4"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Only your current starters can be locked."));
    }

    @Test
    void rejectsMalformedPlayerIds() throws Exception {
        mvc.perform(put("/api/leagues/111/users/222/lock-in/abc")).andExpect(status().isBadRequest());

        verify(advisor, never()).lock(any(), any(), any());
    }
}
