package com.fantasy.planner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.IntStream;

import org.springframework.stereotype.Service;

import com.fantasy.league.InvalidRequestException;
import com.fantasy.league.LeagueData;
import com.fantasy.league.SeasonCalendar;
import com.fantasy.lineup.LineupSolver;
import com.fantasy.lineup.LineupSolver.Candidate;
import com.fantasy.planner.PlannerResponse.Cell;
import com.fantasy.planner.PlannerResponse.Row;
import com.fantasy.planner.PlannerResponse.Shortage;
import com.fantasy.player.PlayerSummary;
import com.fantasy.sleeper.SleeperGame;
import com.fantasy.sleeper.SleeperLeague;
import com.fantasy.stats.ScoringCalculator;
import com.fantasy.trade.SeasonValuer;

/**
 * Maps the user's roster across the remaining weeks. Long-term absences (IR, PUP,
 * suspension) count for every week; game-day tags (Out, Doubtful, Questionable) only
 * for this week, since future injury reports aren't known. A shortage is a week in
 * which the available players can't fill every starting slot.
 */
@Service
public class PlannerService {

    private static final Set<String> LONG_TERM_ABSENCES = Set.of("IR", "PUP", "Sus", "NA");
    private static final Set<String> AVAILABLE = Set.of("ok", "questionable", "played");
    private static final List<String> POSITION_ORDER = List.of("QB", "RB", "WR", "TE", "K", "DEF");

    private final LeagueData leagueData;
    private final SeasonCalendar calendar;
    private final SeasonValuer valuer;

    public PlannerService(LeagueData leagueData, SeasonCalendar calendar, SeasonValuer valuer) {
        this.leagueData = leagueData;
        this.calendar = calendar;
        this.valuer = valuer;
    }

    public PlannerResponse plan(String leagueId, String userId) {
        LeagueData.Snapshot snapshot = leagueData.load(leagueId);
        SleeperLeague league = snapshot.league();
        LeagueData.Team team = snapshot.teamOf(userId);
        int currentWeek = calendar.currentWeek(league);
        int lastWeek = SeasonCalendar.lastFantasyWeek(league.settings());
        if (currentWeek > lastWeek) {
            throw new InvalidRequestException("This league's season is over (it ended in week " + lastWeek + ")");
        }
        SeasonValuer.Season season = valuer.load(league, currentWeek, lastWeek);
        List<Integer> weeks = IntStream.rangeClosed(currentWeek, lastWeek).boxed().toList();

        Set<String> taxi = new HashSet<>(team.roster().taxi() != null ? team.roster().taxi() : List.of());
        Set<String> starters = new HashSet<>(team.roster().starters() != null ? team.roster().starters() : List.of());
        List<PlayerSummary> players = team.players().stream().filter(Objects::nonNull)
                .filter(p -> !taxi.contains(p.playerId()))
                .sorted(Comparator.comparing((PlayerSummary p) -> !starters.contains(p.playerId()))
                        .thenComparingInt(p -> positionOrder(p.position()))
                        .thenComparing(p -> p.name() != null ? p.name() : ""))
                .toList();

        List<Row> rows = players.stream()
                .map(p -> new Row(p, starters.contains(p.playerId()),
                        weeks.stream().map(w -> cell(p, w, season)).toList()))
                .toList();

        List<String> slots = LineupSolver.startingSlots(snapshot.rosterPositions());
        List<Shortage> shortages = new ArrayList<>();
        for (int i = 0; i < weeks.size(); i++) {
            int index = i;
            List<Candidate> available = rows.stream()
                    .filter(r -> AVAILABLE.contains(r.cells().get(index).status()))
                    .map(r -> new Candidate(r.player(), 1, false))
                    .toList();
            List<Candidate> lineup = LineupSolver.solve(slots, List.of(), available);
            List<String> unfilled = IntStream.range(0, slots.size())
                    .filter(s -> lineup.get(s) == null && LineupSolver.isScored(slots.get(s)))
                    .mapToObj(slots::get).toList();
            if (!unfilled.isEmpty()) {
                shortages.add(new Shortage(weeks.get(i), unfilled, "Week %d: no available player for %s."
                        .formatted(weeks.get(i), String.join(", ", unfilled))));
            }
        }

        List<String> notes = new ArrayList<>(season.notes());
        notes.add("Out, Doubtful and Questionable tags only apply to this week; injured reserve counts for every week.");
        if (season.gamesByWeekAndTeam() == null) {
            notes.add("Byes aren't shown because the NFL schedule is unavailable right now.");
        }
        return new PlannerResponse(weeks, rows, shortages, notes);
    }

    private static Cell cell(PlayerSummary player, int week, SeasonValuer.Season season) {
        SleeperGame game = season.gameOf(player, week);
        String opponent = game == null ? null
                : player.team().equals(game.home()) ? "vs " + game.away() : "@ " + game.home();
        Double projected = SeasonValuer.projectedPoints(player, week, season);
        Double rounded = projected != null ? ScoringCalculator.round(projected) : null;
        String injury = player.injuryStatus();

        if (player.team() == null || (season.gamesByWeekAndTeam() != null && game == null)) {
            return new Cell(week, "bye", null, null);
        }
        if (injury != null && LONG_TERM_ABSENCES.contains(injury)) {
            return new Cell(week, "out", rounded, opponent);
        }
        if (week == season.currentWeek()) {
            if (game != null && !"pre_game".equals(game.status())) {
                return new Cell(week, "played", rounded, opponent);
            }
            if ("Out".equals(injury)) {
                return new Cell(week, "out", rounded, opponent);
            }
            if ("Doubtful".equals(injury)) {
                return new Cell(week, "doubtful", rounded, opponent);
            }
            if ("Questionable".equals(injury)) {
                return new Cell(week, "questionable", rounded, opponent);
            }
        }
        return new Cell(week, "ok", rounded, opponent);
    }

    private static int positionOrder(String position) {
        int index = POSITION_ORDER.indexOf(position);
        return index >= 0 ? index : POSITION_ORDER.size();
    }
}
