package com.fantasy.league;

import java.util.List;

import com.fantasy.player.PlayerSummary;

/** One team in a league with every rostered player (starters, bench, IR and taxi), for picking trade targets. */
public record LeagueTeam(int rosterId, RosterResponse.Owner owner, List<PlayerSummary> players) {
}
