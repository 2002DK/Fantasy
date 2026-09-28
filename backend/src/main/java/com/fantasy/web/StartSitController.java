package com.fantasy.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fantasy.startsit.StartSitResponse;
import com.fantasy.startsit.StartSitService;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

@RestController
@RequestMapping("/api/leagues")
public class StartSitController {

    /** Numeric player IDs, or a team code for team defenses (e.g. "LAR"). */
    private static final String PLAYER_ID = "[A-Z0-9]{1,10}";
    private static final String PLAYER_ID_MESSAGE = "must be a Sleeper player ID";

    private final StartSitService startSitService;

    public StartSitController(StartSitService startSitService) {
        this.startSitService = startSitService;
    }

    @GetMapping("/{leagueId}/start-sit")
    public StartSitResponse compare(
            @PathVariable @Pattern(regexp = "\\d{1,25}", message = "must be a numeric Sleeper ID") String leagueId,
            @RequestParam @Pattern(regexp = PLAYER_ID, message = PLAYER_ID_MESSAGE) String playerA,
            @RequestParam @Pattern(regexp = PLAYER_ID, message = PLAYER_ID_MESSAGE) String playerB,
            @RequestParam(required = false) @Min(value = 1, message = "must be between 1 and 18")
                    @Max(value = 18, message = "must be between 1 and 18") Integer week) {
        return startSitService.compare(leagueId, playerA, playerB, week);
    }
}
