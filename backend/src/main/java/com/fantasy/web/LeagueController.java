package com.fantasy.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fantasy.league.LeagueService;
import com.fantasy.league.UserLeaguesResponse;

import jakarta.validation.constraints.Pattern;

@RestController
@RequestMapping("/api/users")
public class LeagueController {

    private final LeagueService leagueService;

    public LeagueController(LeagueService leagueService) {
        this.leagueService = leagueService;
    }

    @GetMapping("/{username}/leagues")
    public UserLeaguesResponse getLeagues(
            @PathVariable @Pattern(regexp = "\\w{1,40}", message = "must be letters, digits or underscores") String username,
            @RequestParam(required = false) @Pattern(regexp = "\\d{4}", message = "must be a four-digit year") String season) {
        return leagueService.findLeagues(username, season);
    }
}
