package com.fantasy.league;

import com.fantasy.sleeper.SleeperLeague;
import com.fantasy.sleeper.Sport;

/**
 * Which tools support which sport. Basketball support arrives in phases; until a tool
 * supports basketball it refuses basketball leagues, rather than scoring basketball
 * players with football projections.
 */
public final class SportSupport {

    private SportSupport() {
    }

    public static void requireFootball(SleeperLeague league, String tool) {
        if (league.sportType() != Sport.NFL) {
            throw new InvalidRequestException(
                    tool + " isn't available for " + league.sportType().displayName().toLowerCase()
                            + " leagues yet; it's coming in a later update.");
        }
    }
}
