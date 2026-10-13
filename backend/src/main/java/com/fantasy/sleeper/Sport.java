package com.fantasy.sleeper;

/**
 * A Sleeper sport. Sleeper uses the same endpoints for each sport with the code in the
 * path (e.g. /players/nba), and player IDs are only unique within a sport: most
 * basketball IDs are also football IDs for different people.
 */
public enum Sport {
    NFL("nfl", "Football"),
    NBA("nba", "Basketball");

    private final String code;
    private final String displayName;

    Sport(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    /** The code Sleeper uses in URLs and in a league's {@code sport} field. */
    public String code() {
        return code;
    }

    public String displayName() {
        return displayName;
    }

    /** Leagues without a sport field predate basketball support and are football. */
    public static Sport fromCode(String code) {
        for (Sport sport : values()) {
            if (sport.code.equalsIgnoreCase(code)) {
                return sport;
            }
        }
        return NFL;
    }
}
