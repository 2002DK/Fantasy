package com.fantasy.league;

import org.springframework.stereotype.Component;

import com.fantasy.sleeper.SeasonState;
import com.fantasy.sleeper.SleeperClient;
import com.fantasy.sleeper.SleeperLeague;
import com.fantasy.sleeper.Sport;

/** Which week it is in a league's sport, and where a league's regular season and playoffs end. */
@Component
public class SeasonCalendar {

    public static final int LAST_NFL_WEEK = 18;
    /** The basketball schedule has 25 weeks, the last ending with the regular season in April. */
    public static final int LAST_NBA_WEEK = 25;
    /** Sleeper's default: playoffs start in week 15, so the regular season ends in week 14. */
    public static final int DEFAULT_REGULAR_SEASON_END = 14;
    /** Last fantasy week when the league has not set its playoff start. */
    public static final int DEFAULT_LAST_WEEK = 17;
    /** Basketball leagues usually start playoffs in week 21 (of 25); assumed when unset. */
    public static final int DEFAULT_NBA_PLAYOFF_START = 21;
    static final int DEFAULT_PLAYOFF_TEAMS = 6;

    private final SleeperClient sleeperClient;

    public SeasonCalendar(SleeperClient sleeperClient) {
        this.sleeperClient = sleeperClient;
    }

    /**
     * The current week of the league's sport, for a league in that sport's current season.
     * Features that look at "this week" or "the rest of the season" only make sense then.
     */
    public int currentWeek(SleeperLeague league) {
        Sport sport = league.sportType();
        SeasonState state = sleeperClient.getState(sport);
        if (!league.season().equals(state.season())) {
            throw new InvalidRequestException(
                    "This league is from the " + league.season() + " season; this only works for the current season");
        }
        // Basketball's preseason counts its own weeks (week 3 days before opening night); fantasy week 1 is next
        if (sport == Sport.NBA && "pre".equals(state.seasonType())) {
            return 1;
        }
        return Math.clamp(state.week(), 1, sport == Sport.NBA ? LAST_NBA_WEEK : LAST_NFL_WEEK);
    }

    /** The final week of the league's playoffs, in its sport's calendar. */
    public static int lastFantasyWeek(SleeperLeague league) {
        if (league.sportType() != Sport.NBA) {
            return lastFantasyWeek(league.settings());
        }
        SleeperLeague.Settings settings = league.settings();
        int start = playoffStartIsSet(settings) ? settings.playoffWeekStart() : DEFAULT_NBA_PLAYOFF_START;
        int teams = settings != null && settings.playoffTeams() > 0 ? settings.playoffTeams() : DEFAULT_PLAYOFF_TEAMS;
        return Math.min(start + rounds(teams) - 1, LAST_NBA_WEEK);
    }

    /** The last regular-season week, in the league's sport's calendar. */
    public static int regularSeasonEnd(SleeperLeague league) {
        if (league.sportType() != Sport.NBA) {
            return regularSeasonEnd(league.settings());
        }
        return (playoffStartIsSet(league.settings()) ? league.settings().playoffWeekStart()
                : DEFAULT_NBA_PLAYOFF_START) - 1;
    }

    private static int rounds(int playoffTeams) {
        int teams = Math.max(playoffTeams, 2);
        return 32 - Integer.numberOfLeadingZeros(teams - 1); // ceil(log2(teams)): 6 teams -> 3 rounds
    }

    /** Football: the final week of the league's playoffs, or week 17 when the league has not set a playoff start. */
    public static int lastFantasyWeek(SleeperLeague.Settings settings) {
        if (settings == null || settings.playoffWeekStart() <= 0) {
            return DEFAULT_LAST_WEEK;
        }
        return Math.min(settings.playoffWeekStart() + rounds(settings.playoffTeams()) - 1, LAST_NFL_WEEK);
    }

    /** Football: the last regular-season week, the week before playoffs start, or week 14 when unset. */
    public static int regularSeasonEnd(SleeperLeague.Settings settings) {
        if (settings == null || settings.playoffWeekStart() <= 0) {
            return DEFAULT_REGULAR_SEASON_END;
        }
        return settings.playoffWeekStart() - 1;
    }

    public static boolean playoffStartIsSet(SleeperLeague.Settings settings) {
        return settings != null && settings.playoffWeekStart() > 0;
    }
}
