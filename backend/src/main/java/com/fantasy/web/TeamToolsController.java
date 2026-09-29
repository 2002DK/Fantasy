package com.fantasy.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fantasy.lineup.LineupResponse;
import com.fantasy.lineup.LineupService;
import com.fantasy.matchup.MatchupResponse;
import com.fantasy.matchup.MatchupService;
import com.fantasy.planner.PlannerResponse;
import com.fantasy.planner.PlannerService;
import com.fantasy.player.PlayerDetailResponse;
import com.fantasy.player.PlayerDetailService;
import com.fantasy.standings.StandingsResponse;
import com.fantasy.standings.StandingsService;
import com.fantasy.trade.TradeFinderService;
import com.fantasy.trade.TradeIdeasResponse;

import jakarta.validation.constraints.Pattern;

/** Lineup, matchup, standings, trade ideas, planner and player detail for a league. */
@RestController
@RequestMapping("/api/leagues")
public class TeamToolsController {

    private static final String SLEEPER_ID = "\\d{1,25}";
    private static final String SLEEPER_ID_MESSAGE = "must be a numeric Sleeper ID";
    /** Numeric player IDs, or a team code for team defenses (e.g. "LAR"). */
    private static final String PLAYER_ID = "[A-Z0-9]{1,10}";

    private final LineupService lineupService;
    private final MatchupService matchupService;
    private final StandingsService standingsService;
    private final TradeFinderService tradeFinderService;
    private final PlannerService plannerService;
    private final PlayerDetailService playerDetailService;

    public TeamToolsController(LineupService lineupService, MatchupService matchupService,
            StandingsService standingsService, TradeFinderService tradeFinderService, PlannerService plannerService,
            PlayerDetailService playerDetailService) {
        this.lineupService = lineupService;
        this.matchupService = matchupService;
        this.standingsService = standingsService;
        this.tradeFinderService = tradeFinderService;
        this.plannerService = plannerService;
        this.playerDetailService = playerDetailService;
    }

    @GetMapping("/{leagueId}/users/{userId}/lineup")
    public LineupResponse lineup(
            @PathVariable @Pattern(regexp = SLEEPER_ID, message = SLEEPER_ID_MESSAGE) String leagueId,
            @PathVariable @Pattern(regexp = SLEEPER_ID, message = SLEEPER_ID_MESSAGE) String userId) {
        return lineupService.optimize(leagueId, userId);
    }

    @GetMapping("/{leagueId}/users/{userId}/matchup")
    public MatchupResponse matchup(
            @PathVariable @Pattern(regexp = SLEEPER_ID, message = SLEEPER_ID_MESSAGE) String leagueId,
            @PathVariable @Pattern(regexp = SLEEPER_ID, message = SLEEPER_ID_MESSAGE) String userId) {
        return matchupService.preview(leagueId, userId);
    }

    @GetMapping("/{leagueId}/users/{userId}/planner")
    public PlannerResponse planner(
            @PathVariable @Pattern(regexp = SLEEPER_ID, message = SLEEPER_ID_MESSAGE) String leagueId,
            @PathVariable @Pattern(regexp = SLEEPER_ID, message = SLEEPER_ID_MESSAGE) String userId) {
        return plannerService.plan(leagueId, userId);
    }

    @GetMapping("/{leagueId}/users/{userId}/trade-ideas")
    public TradeIdeasResponse tradeIdeas(
            @PathVariable @Pattern(regexp = SLEEPER_ID, message = SLEEPER_ID_MESSAGE) String leagueId,
            @PathVariable @Pattern(regexp = SLEEPER_ID, message = SLEEPER_ID_MESSAGE) String userId) {
        return tradeFinderService.findIdeas(leagueId, userId);
    }

    /** {@code userId} is optional and marks the caller's team. */
    @GetMapping("/{leagueId}/standings")
    public StandingsResponse standings(
            @PathVariable @Pattern(regexp = SLEEPER_ID, message = SLEEPER_ID_MESSAGE) String leagueId,
            @RequestParam(required = false) @Pattern(regexp = SLEEPER_ID, message = SLEEPER_ID_MESSAGE) String userId) {
        return standingsService.standings(leagueId, userId);
    }

    @GetMapping("/{leagueId}/players/{playerId}")
    public PlayerDetailResponse player(
            @PathVariable @Pattern(regexp = SLEEPER_ID, message = SLEEPER_ID_MESSAGE) String leagueId,
            @PathVariable @Pattern(regexp = PLAYER_ID, message = "must be a Sleeper player ID") String playerId,
            @RequestParam(required = false) @Pattern(regexp = SLEEPER_ID, message = SLEEPER_ID_MESSAGE) String userId) {
        return playerDetailService.detail(leagueId, playerId, userId);
    }
}
