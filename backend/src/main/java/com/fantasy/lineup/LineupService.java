package com.fantasy.lineup;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.fantasy.league.LeagueData;
import com.fantasy.league.SeasonCalendar;
import com.fantasy.lineup.LineupResponse.LineupPlayer;
import com.fantasy.lineup.LineupSolver.Candidate;
import com.fantasy.player.PlayerSummary;
import com.fantasy.sleeper.SleeperRoster;
import com.fantasy.startsit.StartSitResponse.PlayerAnalysis;
import com.fantasy.startsit.WeeklyScorer;
import com.fantasy.stats.ScoringCalculator;

/** Finds the lineup with the most expected points this week and explains how it differs from the current one. */
@Service
public class LineupService {

    private final LeagueData leagueData;
    private final SeasonCalendar calendar;
    private final WeeklyScorer scorer;

    public LineupService(LeagueData leagueData, SeasonCalendar calendar, WeeklyScorer scorer) {
        this.leagueData = leagueData;
        this.calendar = calendar;
        this.scorer = scorer;
    }

    public LineupResponse optimize(String leagueId, String userId) {
        LeagueData.Snapshot snapshot = leagueData.load(leagueId);
        LeagueData.Team team = snapshot.teamOf(userId);
        int week = calendar.currentWeek(snapshot.league());
        WeeklyScorer.Week data = scorer.load(snapshot.league(), week);

        SleeperRoster roster = team.roster();
        Set<String> unavailableForLineup = new HashSet<>();
        unavailableForLineup.addAll(orEmpty(roster.reserve()));
        unavailableForLineup.addAll(orEmpty(roster.taxi()));

        Map<String, LineupPlayer> byId = new HashMap<>();
        List<Candidate> candidates = new ArrayList<>();
        for (PlayerSummary player : team.players()) {
            LineupPlayer lineupPlayer = lineupPlayer(player, data);
            byId.put(player.playerId(), lineupPlayer);
            if (!unavailableForLineup.contains(player.playerId())) {
                candidates.add(new Candidate(player, lineupPlayer.expectedPoints(), lineupPlayer.locked()));
            }
        }

        List<String> slots = LineupSolver.startingSlots(snapshot.rosterPositions());
        List<String> currentIds = orEmpty(roster.starters());
        List<Candidate> optimal = LineupSolver.solve(slots, currentIds, candidates);

        List<LineupResponse.Slot> result = new ArrayList<>();
        double currentTotal = 0;
        double optimalTotal = 0;
        for (int i = 0; i < slots.size(); i++) {
            String currentId = i < currentIds.size() ? currentIds.get(i) : null;
            LineupPlayer current = currentId != null ? byId.get(currentId) : null;
            LineupPlayer best = optimal.get(i) != null ? byId.get(optimal.get(i).player().playerId()) : null;
            currentTotal += current != null ? current.expectedPoints() : 0;
            optimalTotal += best != null ? best.expectedPoints() : 0;
            result.add(new LineupResponse.Slot(slots.get(i), current, best, LineupSolver.isScored(slots.get(i))));
        }

        List<String> notes = new ArrayList<>(data.notes());
        if (result.stream().anyMatch(s -> !s.scored())) {
            notes.add("Defensive player slots are left as they are; this app has no projections for those players.");
        }
        if (result.stream().anyMatch(s -> s.current() != null && s.current().locked())) {
            notes.add("Players whose games have started are locked in place.");
        }
        return new LineupResponse(week, result, ScoringCalculator.round(currentTotal),
                ScoringCalculator.round(optimalTotal), ScoringCalculator.round(optimalTotal - currentTotal),
                changes(result), notes);
    }

    private LineupPlayer lineupPlayer(PlayerSummary player, WeeklyScorer.Week data) {
        PlayerAnalysis analysis = scorer.analyze(player, data);
        WeeklyScorer.Opponent opponent = data.opponentOf(player);
        String opponentLabel = opponent == null ? null : (opponent.home() ? "vs " : "@ ") + opponent.team();
        double points = analysis.score() != null ? analysis.score() : 0;
        return new LineupPlayer(player, points, analysis.available(), analysis.availabilityNote(), opponentLabel,
                data.isLocked(player));
    }

    /** Pairs players entering the lineup with those leaving it, best newcomer against worst departure. */
    static List<String> changes(List<LineupResponse.Slot> slots) {
        Set<String> currentIds = new HashSet<>();
        Set<String> optimalIds = new HashSet<>();
        slots.forEach(s -> {
            if (s.current() != null) {
                currentIds.add(s.current().player().playerId());
            }
            if (s.optimal() != null) {
                optimalIds.add(s.optimal().player().playerId());
            }
        });
        List<LineupPlayer> incoming = slots.stream().map(LineupResponse.Slot::optimal).filter(Objects::nonNull)
                .filter(p -> !currentIds.contains(p.player().playerId()))
                .sorted(Comparator.comparingDouble(LineupPlayer::expectedPoints).reversed()).toList();
        List<LineupPlayer> outgoing = slots.stream().map(LineupResponse.Slot::current).filter(Objects::nonNull)
                .filter(p -> !optimalIds.contains(p.player().playerId()))
                .sorted(Comparator.comparingDouble(LineupPlayer::expectedPoints)).toList();

        List<String> changes = new ArrayList<>();
        for (int i = 0; i < incoming.size(); i++) {
            LineupPlayer in = incoming.get(i);
            if (i < outgoing.size()) {
                LineupPlayer out = outgoing.get(i);
                changes.add("Start %s (%s) instead of %s (%s)%s: +%s expected points."
                        .formatted(name(in), fmt(in.expectedPoints()), name(out), fmt(out.expectedPoints()),
                                out.available() ? "" : ", who is " + describe(out.availabilityNote()),
                                fmt(in.expectedPoints() - out.expectedPoints())));
            } else {
                changes.add("Start %s in the empty slot: +%s expected points.".formatted(name(in), fmt(in.expectedPoints())));
            }
        }
        return changes;
    }

    private static String describe(String availabilityNote) {
        return switch (availabilityNote) {
            case "On bye" -> "on bye";
            case "Not on an NFL team" -> "not on an NFL team";
            default -> "listed as " + availabilityNote;
        };
    }

    private static String name(LineupPlayer p) {
        return Optional.ofNullable(p.player().name()).orElse("Player " + p.player().playerId());
    }

    private static String fmt(double points) {
        return "%.1f".formatted(points);
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list != null ? list : List.of();
    }
}
