package com.fantasy.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import com.fantasy.league.LeagueTeam;
import com.fantasy.league.RosterResponse;
import com.fantasy.league.RosterService;

import jakarta.validation.constraints.Pattern;

@RestController
@RequestMapping("/api/leagues")
public class RosterController {

    private static final String SLEEPER_ID = "\\d{1,25}";
    private static final String SLEEPER_ID_MESSAGE = "must be a numeric Sleeper ID";

    private final RosterService rosterService;

    public RosterController(RosterService rosterService) {
        this.rosterService = rosterService;
    }

    @GetMapping("/{leagueId}/rosters")
    public List<LeagueTeam> getAllTeams(
            @PathVariable @Pattern(regexp = SLEEPER_ID, message = SLEEPER_ID_MESSAGE) String leagueId) {
        return rosterService.findAllTeams(leagueId);
    }

    @GetMapping("/{leagueId}/users/{userId}/roster")
    public RosterResponse getRoster(
            @PathVariable @Pattern(regexp = SLEEPER_ID, message = SLEEPER_ID_MESSAGE) String leagueId,
            @PathVariable @Pattern(regexp = SLEEPER_ID, message = SLEEPER_ID_MESSAGE) String userId) {
        return rosterService.findRoster(leagueId, userId);
    }
}
