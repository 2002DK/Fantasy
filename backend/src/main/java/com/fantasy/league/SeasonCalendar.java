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
    /** Sleeper basketball seasons run about 25 weeks including playoffs. */
    public static final int LAST_NBA_WEEK = 26;
    /** Sleeper's default: playoffs start in week 15, so the regular season ends in week 14. */
    public static final int DEFAULT_REGULAR_SEASON_END = 14;
    /** Last fantasy week when the league has not set its playoff start. */
    public static final int DEFAULT_LAST_WEEK = 17;

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
        return Math.clamp(state.week(), 1, sport == Sport.NBA ? LAST_NBA_WEEK : LAST_NFL_WEEK);
    }

    /** The final week of the league's playoffs, or week 17 when the league has not set a playoff start. */
    public static int lastFantasyWeek(SleeperLeague.Settings settings) {
        if (settings == null || settings.playoffWeekStart() <= 0) {
            return DEFAULT_LAST_WEEK;
        }
        int teams = Math.max(settings.playoffTeams(), 2);
        int rounds = 32 - Integer.numberOfLeadingZeros(teams - 1); // ceil(log2(teams)): 6 teams -> 3 rounds
        return Math.min(settings.playoffWeekStart() + rounds - 1, LAST_NFL_WEEK);
    }

    /** The last regular-season week: the week before playoffs start, or week 14 when unset. */
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
