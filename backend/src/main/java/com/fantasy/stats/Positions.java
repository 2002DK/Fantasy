package com.fantasy.stats;

import java.util.Map;

public final class Positions {

    private static final Map<String, String> PLURAL_NAMES = Map.ofEntries(
            Map.entry("QB", "quarterbacks"), Map.entry("RB", "running backs"), Map.entry("WR", "receivers"),
            Map.entry("TE", "tight ends"), Map.entry("K", "kickers"), Map.entry("DEF", "defenses"),
            Map.entry("PG", "point guards"), Map.entry("SG", "shooting guards"), Map.entry("SF", "small forwards"),
            Map.entry("PF", "power forwards"), Map.entry("C", "centers"));

    private Positions() {
    }

    /** "running backs" for RB; falls back to the code plus "s", or "this position" when unknown. */
    public static String pluralName(String position) {
        return position != null ? PLURAL_NAMES.getOrDefault(position, position + "s") : "this position";
    }
}
