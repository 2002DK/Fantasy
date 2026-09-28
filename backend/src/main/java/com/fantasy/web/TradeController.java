package com.fantasy.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fantasy.trade.TradeResponse;
import com.fantasy.trade.TradeService;

import jakarta.validation.constraints.Pattern;

@RestController
@RequestMapping("/api/leagues")
public class TradeController {

    /** Numeric player IDs, or a team code for team defenses (e.g. "LAR"). */
    private static final String PLAYER_ID = "[A-Z0-9]{1,10}";
    private static final String PLAYER_ID_MESSAGE = "must list Sleeper player IDs";

    private final TradeService tradeService;

    public TradeController(TradeService tradeService) {
        this.tradeService = tradeService;
    }

    /** {@code give} and {@code get} are comma-separated player IDs, from the caller's side of the trade. */
    @GetMapping("/{leagueId}/trade")
    public TradeResponse analyze(
            @PathVariable @Pattern(regexp = "\\d{1,25}", message = "must be a numeric Sleeper ID") String leagueId,
            @RequestParam List<@Pattern(regexp = PLAYER_ID, message = PLAYER_ID_MESSAGE) String> give,
            @RequestParam List<@Pattern(regexp = PLAYER_ID, message = PLAYER_ID_MESSAGE) String> get) {
        return tradeService.analyze(leagueId, give, get);
    }
}
