package com.fantasy.web;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fantasy.lockin.LockInAdviceResponse;
import com.fantasy.lockin.LockInAdvisorService;

import jakarta.validation.constraints.Pattern;

/**
 * Lock-or-pass advice for a basketball Lock-In league, and the user's marked locks.
 * Marking returns the refreshed advice so the page can update in one round trip.
 */
@RestController
@RequestMapping("/api/leagues/{leagueId}/users/{userId}/lock-in")
public class LockInController {

    private static final String SLEEPER_ID = "\\d{1,25}";
    private static final String SLEEPER_ID_MESSAGE = "must be a numeric Sleeper ID";
    private static final String PLAYER_ID = "\\d{1,10}";

    private final LockInAdvisorService advisor;

    public LockInController(LockInAdvisorService advisor) {
        this.advisor = advisor;
    }

    @GetMapping
    public LockInAdviceResponse advice(
            @PathVariable @Pattern(regexp = SLEEPER_ID, message = SLEEPER_ID_MESSAGE) String leagueId,
            @PathVariable @Pattern(regexp = SLEEPER_ID, message = SLEEPER_ID_MESSAGE) String userId) {
        return advisor.advice(leagueId, userId);
    }

    @PutMapping("/{playerId}")
    public LockInAdviceResponse lock(
            @PathVariable @Pattern(regexp = SLEEPER_ID, message = SLEEPER_ID_MESSAGE) String leagueId,
            @PathVariable @Pattern(regexp = SLEEPER_ID, message = SLEEPER_ID_MESSAGE) String userId,
            @PathVariable @Pattern(regexp = PLAYER_ID, message = "must be a Sleeper player ID") String playerId) {
        return advisor.lock(leagueId, userId, playerId);
    }

    @DeleteMapping("/{playerId}")
    public LockInAdviceResponse unlock(
            @PathVariable @Pattern(regexp = SLEEPER_ID, message = SLEEPER_ID_MESSAGE) String leagueId,
            @PathVariable @Pattern(regexp = SLEEPER_ID, message = SLEEPER_ID_MESSAGE) String userId,
            @PathVariable @Pattern(regexp = PLAYER_ID, message = "must be a Sleeper player ID") String playerId) {
        return advisor.unlock(leagueId, userId, playerId);
    }
}
