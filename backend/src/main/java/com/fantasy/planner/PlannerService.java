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
import com.fantasy.lockin.BasketballSeason;
import com.fantasy.lockin.BasketballSeasonValuer;
import com.fantasy.lockin.LockInScorer;
import com.fantasy.planner.PlannerResponse.Cell;
import com.fantasy.planner.PlannerResponse.Row;
import com.fantasy.planner.PlannerResponse.Shortage;
import com.fantasy.player.PlayerSummary;
import com.fantasy.sleeper.SleeperGame;
import com.fantasy.sleeper.SleeperLeague;
import com.fantasy.sleeper.Sport;
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
    private static final List<String> POSITION_ORDER = List.of("QB", "RB", "WR", "TE", "K", "DEF",
            "PG", "SG", "SF", "PF", "C");
    /** Basketball statuses that keep a player out for weeks. */
    private static final Set<String> NBA_LONG_TERM = Set.of("IR", "Sus", "NA");
    /** Basketball weeks below this many games per starter leave little room to wait for a better game. */
    static final double LIGHT_WEEK_GAMES = 2.0;

    private final LeagueData leagueData;
    private final SeasonCalendar calendar;
    private final SeasonValuer valuer;
    private final BasketballSeasonValuer basketballValuer;

    public PlannerService(LeagueData leagueData, SeasonCalendar calendar, SeasonValuer valuer,
            BasketballSeasonValuer basketballValuer) {
        this.leagueData = leagueData;
        this.calendar = calendar;
        this.valuer = valuer;
        this.basketballValuer = basketballValuer;
    }

    public PlannerResponse plan(String leagueId, String userId) {
        LeagueData.Snapshot snapshot = leagueData.load(leagueId);
        SleeperLeague league = snapshot.league();
        LeagueData.Team team = snapshot.teamOf(userId);
        int currentWeek = calendar.currentWeek(league);
        int lastWeek = SeasonCalendar.lastFantasyWeek(league);
        if (currentWeek > lastWeek) {
            throw new InvalidRequestException("This league's season is over (it ended in week " + lastWeek + ")");
        }
        if (league.sportType() == Sport.NBA) {
            return planBasketball(snapshot, team, currentWeek, lastWeek);
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
        List<Shortage> shortages = shortages(slots, weeks, rows);

        List<String> notes = new ArrayList<>(season.notes());
        notes.add("Out, Doubtful and Questionable tags only apply to this week; injured reserve counts for every week.");
        if (season.gamesByWeekAndTeam() == null) {
            notes.add("Byes aren't shown because the NFL schedule is unavailable right now.");
        }
        return new PlannerResponse(weeks, rows, shortages, notes);
    }

    /** Weeks in which the available players can't fill every starting slot. */
    private static List<Shortage> shortages(List<String> slots, List<Integer> weeks, List<Row> rows) {
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
        return shortages;
    }

    /**
     * Basketball: games per player per week instead of byes, with each week's Lock-In
     * value. Besides unfillable slots, a week is flagged when the best lineup averages
     * under {@link #LIGHT_WEEK_GAMES} games per starter.
     */
    private PlannerResponse planBasketball(LeagueData.Snapshot snapshot, LeagueData.Team team, int currentWeek,
            int lastWeek) {
        BasketballSeason season = basketballValuer.load(snapshot.league(), currentWeek, lastWeek);
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
                        weeks.stream().map(w -> basketballCell(p, w, season)).toList()))
                .toList();

        List<String> slots = LineupSolver.startingSlots(snapshot.rosterPositions());
        List<Shortage> shortages = new ArrayList<>(shortages(slots, weeks, rows));
        for (int i = 0; i < weeks.size(); i++) {
            int week = weeks.get(i);
            if (week == currentWeek || shortages.stream().anyMatch(s -> s.week() == week)) {
                continue;
            }
            int index = i;
            List<Candidate> candidates = rows.stream()
                    .filter(r -> AVAILABLE.contains(r.cells().get(index).status()))
                    .map(r -> new Candidate(r.player(), r.cells().get(index).projected() != null
                            ? r.cells().get(index).projected() : 0, false))
                    .toList();
            List<Candidate> lineup = LineupSolver.solve(slots, List.of(), candidates);
            double games = lineup.stream().filter(Objects::nonNull)
                    .mapToInt(c -> season.week(c.player(), week).games()).average().orElse(0);
            if (games < LIGHT_WEEK_GAMES) {
                shortages.add(new Shortage(week, List.of(), ("Week %d: your best lineup averages %s games per "
                        + "starter, so there's little room to wait for a better game.")
                        .formatted(week, ScoringCalculator.oneDecimal(games))));
            }
        }
        shortages.sort(Comparator.comparingInt(Shortage::week));

        List<String> notes = new ArrayList<>(season.notes());
        notes.add("Cells show each week's games and Lock-In value. Out and day-to-day tags only apply to this week; "
                + "injured reserve counts for every week.");
        return new PlannerResponse(weeks, rows, shortages, notes);
    }

    private static Cell basketballCell(PlayerSummary player, int week, BasketballSeason season) {
        List<LockInScorer.ScheduledGame> scheduled = season.gamesOf(player, week);
        BasketballSeason.Week outlook = season.week(player, week);
        Double value = ScoringCalculator.round(outlook.outcome().value());
        String injury = player.injuryStatus();
        if (player.team() == null || scheduled.isEmpty()) {
            return new Cell(week, "bye", null, null, 0);
        }
        int games = scheduled.size();
        if (week == season.currentWeek()) {
            games = (int) scheduled.stream().filter(g -> !g.complete()).count();
            if (games == 0) {
                return new Cell(week, "played", null, "Done", 0);
            }
        }
        String label = games == 1 ? "1 game" : games + " games";
        if (injury != null && NBA_LONG_TERM.contains(injury)) {
            return new Cell(week, "out", value, label, games);
        }
        if (week == season.currentWeek() && injury != null) {
            if ("Out".equals(injury) && outlook.games() == 0) {
                return new Cell(week, "out", value, label, games);
            }
            if ("Doubtful".equals(injury)) {
                return new Cell(week, "doubtful", value, label, games);
            }
            if (Set.of("Out", "DTD", "GTD", "Questionable").contains(injury)) {
                return new Cell(week, "questionable", value, label, games);
            }
        }
        return new Cell(week, "ok", value, label, games);
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
