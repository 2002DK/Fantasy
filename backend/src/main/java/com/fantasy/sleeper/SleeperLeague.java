package com.fantasy.sleeper;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SleeperLeague(
        @JsonProperty("league_id") String leagueId,
        @JsonProperty("name") String name,
        @JsonProperty("season") String season,
        @JsonProperty("status") String status,
        @JsonProperty("total_rosters") int totalRosters,
        @JsonProperty("avatar") String avatar,
        /** Slot per roster position, e.g. QB, RB, FLEX, BN. Starters fill the non-BN slots in order. */
        @JsonProperty("roster_positions") List<String> rosterPositions,
        /** Points per unit of each stat, e.g. rec 0.5 (half PPR), pass_td 6, pass_yd 0.04. */
        @JsonProperty("scoring_settings") Map<String, Double> scoringSettings,
        @JsonProperty("settings") Settings settings,
        /** "nfl" or "nba". */
        @JsonProperty("sport") String sport) {

    /** A football league; the sport field is optional in Sleeper's older data. */
    public SleeperLeague(String leagueId, String name, String season, String status, int totalRosters, String avatar,
            List<String> rosterPositions, Map<String, Double> scoringSettings, Settings settings) {
        this(leagueId, name, season, status, totalRosters, avatar, rosterPositions, scoringSettings, settings, "nfl");
    }

    public Sport sportType() {
        return Sport.fromCode(sport);
    }

    /**
     * playoff_week_start and trade_deadline are 0 when the league has not set them.
     * league_average_match is 1 when teams also play the weekly league median.
     */
    public record Settings(
            @JsonProperty("playoff_week_start") int playoffWeekStart,
            @JsonProperty("playoff_teams") int playoffTeams,
            @JsonProperty("trade_deadline") int tradeDeadline,
            @JsonProperty("league_average_match") int leagueAverageMatch) {

        public Settings(int playoffWeekStart, int playoffTeams, int tradeDeadline) {
            this(playoffWeekStart, playoffTeams, tradeDeadline, 0);
        }

        public boolean playsMedian() {
            return leagueAverageMatch == 1;
        }
    }
}
