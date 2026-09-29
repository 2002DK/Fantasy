package com.fantasy.lineup;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.IntStream;

import com.fantasy.player.PlayerSummary;

/**
 * Picks the starting lineup with the most expected points for a set of roster slots.
 *
 * <p>Dedicated slots (QB, RB...) are filled before flex slots, each with the best
 * eligible player left; that is optimal whenever flex slots nest (FLEX within
 * SUPER_FLEX). A local-improvement pass then handles leagues with overlapping flex
 * slots (WRRB_FLEX and REC_FLEX): it moves a starter to another slot it can fill when
 * that lets a better bench player in. Locked players (game already started) stay
 * where they are, and slots this app cannot score (defensive players) keep their
 * current starter.
 */
public final class LineupSolver {

    private static final Map<String, Set<String>> FLEX_SLOTS = Map.of(
            "FLEX", Set.of("RB", "WR", "TE"),
            "WRRB_FLEX", Set.of("RB", "WR"),
            "REC_FLEX", Set.of("WR", "TE"),
            "SUPER_FLEX", Set.of("QB", "RB", "WR", "TE"));
    private static final Set<String> SCORED_POSITIONS = Set.of("QB", "RB", "WR", "TE", "K", "DEF");

    /** A rostered player with expected points; locked players cannot change slots. */
    public record Candidate(PlayerSummary player, double points, boolean locked) {

        String id() {
            return player.playerId();
        }
    }

    private LineupSolver() {
    }

    /** Positions a slot accepts; empty for slots like BN or IR. */
    public static Set<String> eligiblePositions(String slot) {
        if (FLEX_SLOTS.containsKey(slot)) {
            return FLEX_SLOTS.get(slot);
        }
        return SCORED_POSITIONS.contains(slot) ? Set.of(slot) : Set.of();
    }

    /** Whether the optimizer may change this slot (false for defensive-player slots it cannot score). */
    public static boolean isScored(String slot) {
        return !eligiblePositions(slot).isEmpty();
    }

    public static List<String> startingSlots(List<String> rosterPositions) {
        return rosterPositions.stream().filter(s -> !"BN".equals(s) && !"IR".equals(s) && !"TAXI".equals(s)).toList();
    }

    /**
     * @param slots          starting slots in lineup order
     * @param currentStarters current starter IDs aligned with {@code slots} (null or "0" = empty)
     * @param roster         every player who may start (IR and taxi players excluded by the caller)
     * @return the chosen candidate per slot, aligned with {@code slots}; null where no one can fill it
     */
    public static List<Candidate> solve(List<String> slots, List<String> currentStarters, List<Candidate> roster) {
        Candidate[] lineup = new Candidate[slots.size()];
        boolean[] fixed = new boolean[slots.size()];
        Set<String> used = new HashSet<>();

        // Locked starters and unscored slots keep their current player
        for (int i = 0; i < slots.size(); i++) {
            String currentId = i < currentStarters.size() ? currentStarters.get(i) : null;
            Candidate current = roster.stream().filter(c -> c.id().equals(currentId)).findFirst().orElse(null);
            if (!isScored(slots.get(i)) || (current != null && current.locked())) {
                lineup[i] = current;
                fixed[i] = true;
                if (current != null) {
                    used.add(current.id());
                }
            }
        }

        List<Candidate> free = new ArrayList<>(roster.stream()
                .filter(c -> !c.locked() && !used.contains(c.id()))
                .sorted(Comparator.comparingDouble(Candidate::points).reversed().thenComparing(Candidate::id))
                .toList());

        // Most restrictive open slots first
        List<Integer> open = IntStream.range(0, slots.size()).filter(i -> !fixed[i]).boxed()
                .sorted(Comparator.comparingInt((Integer i) -> eligiblePositions(slots.get(i)).size())
                        .thenComparingInt(i -> i))
                .toList();
        for (int i : open) {
            Set<String> eligible = eligiblePositions(slots.get(i));
            Candidate best = free.stream().filter(c -> eligible.contains(c.player().position())).findFirst().orElse(null);
            if (best != null) {
                lineup[i] = best;
                free.remove(best);
            }
        }

        improve(slots, lineup, open, free);
        // Arrays.asList keeps null entries for slots nobody can fill (List.of would reject them)
        return Arrays.asList(lineup);
    }

    /** Swaps that raise the total: bench-for-starter, or a starter moving slots to make room for a bench player. */
    private static void improve(List<String> slots, Candidate[] lineup, List<Integer> open, List<Candidate> bench) {
        boolean improved = true;
        while (improved) {
            improved = false;
            outer:
            for (int i : open) {
                Set<String> eligibleI = eligiblePositions(slots.get(i));
                for (Candidate b : List.copyOf(bench)) {
                    if (!eligibleI.contains(b.player().position())) {
                        continue;
                    }
                    // Direct replacement
                    if (lineup[i] == null || b.points() > lineup[i].points()) {
                        replace(lineup, i, b, bench);
                        improved = true;
                        break outer;
                    }
                    // Move lineup[i] to slot j, bench lineup[j], put b in slot i
                    for (int j : open) {
                        if (j == i || lineup[i] == null) {
                            continue;
                        }
                        Candidate displaced = lineup[j];
                        boolean fits = eligiblePositions(slots.get(j)).contains(lineup[i].player().position());
                        double gain = b.points() - (displaced != null ? displaced.points() : 0);
                        if (fits && gain > 1e-9) {
                            lineup[j] = lineup[i];
                            lineup[i] = b;
                            bench.remove(b);
                            if (displaced != null) {
                                bench.add(displaced);
                            }
                            improved = true;
                            break outer;
                        }
                    }
                }
            }
        }
    }

    private static void replace(Candidate[] lineup, int slot, Candidate incoming, List<Candidate> bench) {
        Candidate outgoing = lineup[slot];
        lineup[slot] = incoming;
        bench.remove(incoming);
        if (outgoing != null) {
            bench.add(outgoing);
        }
    }

    /** Total expected points of a lineup, ignoring empty slots. */
    public static double total(List<Candidate> lineup) {
        return lineup.stream().filter(Objects::nonNull).mapToDouble(Candidate::points).sum();
    }
}
