package com.fantasy.lineup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fantasy.lineup.LineupSolver.Candidate;
import com.fantasy.player.PlayerSummary;

class LineupSolverTest {

    @Test
    void fillsDedicatedSlotsThenFlexWithBestRemaining() {
        List<Candidate> roster = List.of(
                c("qb1", "QB", 20), c("rb1", "RB", 15), c("rb2", "RB", 9), c("rb3", "RB", 8),
                c("wr1", "WR", 14), c("wr2", "WR", 7), c("te1", "TE", 6));

        List<Candidate> lineup = LineupSolver.solve(List.of("QB", "RB", "WR", "TE", "FLEX"), List.of(), roster);

        assertThat(ids(lineup)).containsExactly("qb1", "rb1", "wr1", "te1", "rb2");
        assertThat(LineupSolver.total(lineup)).isEqualTo(64);
    }

    @Test
    void overlappingFlexSlotsStillReachTheBestTotal() {
        // Greedy by slot order would put WR 10 at REC_FLEX and RB 2 at WRRB_FLEX (12);
        // the best is TE 3 at REC_FLEX and WR 10 at WRRB_FLEX (13)
        List<Candidate> roster = List.of(c("wr", "WR", 10), c("te", "TE", 3), c("rb", "RB", 2));

        List<Candidate> lineup = LineupSolver.solve(List.of("REC_FLEX", "WRRB_FLEX"), List.of(), roster);

        assertThat(ids(lineup)).containsExactly("te", "wr");
        assertThat(LineupSolver.total(lineup)).isEqualTo(13);
    }

    @Test
    void lockedPlayersKeepTheirSlotAndLockedBenchPlayersCannotStart() {
        List<Candidate> roster = List.of(
                new Candidate(p("rbLockedStarter", "RB"), 3, true),
                new Candidate(p("rbLockedBench", "RB"), 30, true),
                c("rbFree", "RB", 12), c("rbFree2", "RB", 5));

        List<Candidate> lineup = LineupSolver.solve(List.of("RB", "RB"), List.of("rbLockedStarter", "rbFree2"), roster);

        assertThat(ids(lineup)).containsExactly("rbLockedStarter", "rbFree");
    }

    @Test
    void defensivePlayerSlotsKeepTheCurrentStarterAndEmptySlotsStayEmpty() {
        List<Candidate> roster = List.of(c("lb1", "LB", 0), c("k1", "K", 8));

        List<Candidate> lineup = LineupSolver.solve(List.of("LB", "K", "DEF"), List.of("lb1", "0", "0"), roster);

        assertThat(ids(lineup)).containsExactly("lb1", "k1", null);
    }

    @Test
    void slotHelpers() {
        assertThat(LineupSolver.eligiblePositions("SUPER_FLEX")).containsExactlyInAnyOrder("QB", "RB", "WR", "TE");
        assertThat(LineupSolver.isScored("DL")).isFalse();
        assertThat(LineupSolver.startingSlots(List.of("QB", "FLEX", "BN", "BN", "IR"))).containsExactly("QB", "FLEX");
    }

    private static List<String> ids(List<Candidate> lineup) {
        return Arrays.asList(lineup.stream().map(c -> c == null ? null : c.player().playerId()).toArray(String[]::new));
    }

    private static Candidate c(String id, String position, double points) {
        return new Candidate(p(id, position), points, false);
    }

    private static PlayerSummary p(String id, String position) {
        return new PlayerSummary(id, id, position, "PHI", null);
    }
}
