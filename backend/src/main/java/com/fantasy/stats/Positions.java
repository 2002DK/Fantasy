package com.fantasy.stats;

import java.util.Map;

public final class Positions {

    private static final Map<String, String> PLURAL_NAMES = Map.of(
            "QB", "quarterbacks", "RB", "running backs", "WR", "receivers",
            "TE", "tight ends", "K", "kickers", "DEF", "defenses");

    private Positions() {
    }

    /** "running backs" for RB; falls back to the code plus "s", or "this position" when unknown. */
    public static String pluralName(String position) {
        return position != null ? PLURAL_NAMES.getOrDefault(position, position + "s") : "this position";
    }
}
