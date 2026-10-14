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
import com.fantasy.lockin.LockInAdviceResponse.PlayedGame;
import com.fantasy.lockin.LockInMark;
import com.fantasy.lockin.LockInMarkRepository;
import com.fantasy.lockin.LockInScorer;
import com.fantasy.player.PlayerSummary;
import com.fantasy.sleeper.SleeperClient;
import com.fantasy.sleeper.SleeperMatchup;
import com.fantasy.sleeper.SleeperRoster;
import com.fantasy.sleeper.Sport;
import com.fantasy.startsit.StartSitResponse.PlayerAnalysis;
import com.fantasy.startsit.WeeklyScorer;
import com.fantasy.stats.ScoringCalculator;

/**
 * Finds the lineup with the most expected points this week and explains how it differs
 * from the current one. Basketball uses each player's Lock-In value for the rest of the
 * week, since one game per starter counts.
 */
@Service
public class LineupService {

    private final LeagueData leagueData;
    private final SeasonCalendar calendar;
    private final WeeklyScorer scorer;
    private final LockInScorer lockInScorer;
    private final SleeperClient sleeperClient;
    private final LockInMarkRepository lockMarks;

    public LineupService(LeagueData leagueData, SeasonCalendar calendar, WeeklyScorer scorer,
            LockInScorer lockInScorer, SleeperClient sleeperClient, LockInMarkRepository lockMarks) {
        this.leagueData = leagueData;
        this.calendar = calendar;
        this.scorer = scorer;
        this.lockInScorer = lockInScorer;
        this.sleeperClient = sleeperClient;
        this.lockMarks = lockMarks;
    }

    public LineupResponse optimize(String leagueId, String userId) {
        LeagueData.Snapshot snapshot = leagueData.load(leagueId);
        LeagueData.Team team = snapshot.teamOf(userId);
        int week = calendar.currentWeek(snapshot.league());
        if (snapshot.league().sportType() == Sport.NBA) {
            return optimizeBasketball(snapshot, team, userId, week);
        }
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

    /**
     * Each player is worth their Lock-In value for the games left this week. A starter
     * who already has a score this week is worth the better of keeping it and waiting
     * (they can lock it before their next game), and stays in place: benching them could
     * lose that score. Players in a game right now can't move. A score the user marked
     * as locked is final.
     */
    private LineupResponse optimizeBasketball(LeagueData.Snapshot snapshot, LeagueData.Team team, String userId,
            int week) {
        LockInScorer.Week data = lockInScorer.load(snapshot.league(), week);
        SleeperRoster roster = team.roster();
        Map<String, Double> counted = countedPoints(snapshot.league().leagueId(), week, team.rosterId());
        Map<String, LockInMark> marked = new HashMap<>();
        lockMarks.findByLeagueIdAndUserIdAndWeek(snapshot.league().leagueId(), userId, week)
                .forEach(m -> marked.put(m.getPlayerId(), m));
        Set<String> starterIds = new HashSet<>(orEmpty(roster.starters()));
        Set<String> unavailableForLineup = new HashSet<>();
        unavailableForLineup.addAll(orEmpty(roster.reserve()));
        unavailableForLineup.addAll(orEmpty(roster.taxi()));

        Map<String, LineupPlayer> byId = new HashMap<>();
        List<Candidate> candidates = new ArrayList<>();
        boolean benchPlayed = false;
        boolean anyScored = false;
        boolean anyPlaying = false;
        for (PlayerSummary player : team.players()) {
            LockInScorer.Analysis analysis = lockInScorer.analyze(player, data);
            double score = counted.getOrDefault(player.playerId(), 0.0);
            boolean starter = starterIds.contains(player.playerId());
            boolean hasScore = starter && score > 0 && data.gamesPlayed(player) > 0;
            LockInMark manual = starter ? marked.get(player.playerId()) : null;
            PlayedGame inferred = starter && manual == null ? data.inferredLock(player.playerId(), score) : null;
            Double lockedPoints = manual != null ? Double.valueOf(manual.getPoints())
                    : inferred != null ? Double.valueOf(inferred.points()) : null;
            double value = lockedPoints != null ? lockedPoints
                    : hasScore ? Math.max(score, analysis.value()) : analysis.value();
            boolean locked = lockedPoints != null || hasScore || data.isPlaying(player);
            String label = lockedPoints != null ? "Locked in at " + fmt(lockedPoints)
                    : gamesLabel(analysis.games().size(), hasScore ? score : null);
            anyScored |= hasScore || lockedPoints != null;
            anyPlaying |= data.isPlaying(player);
            benchPlayed |= !starter && data.gamesPlayed(player) > 0;
            LineupPlayer lineupPlayer = new LineupPlayer(player, ScoringCalculator.round(value),
                    analysis.available() || hasScore || lockedPoints != null, analysis.availabilityNote(), label,
                    locked);
            byId.put(player.playerId(), lineupPlayer);
            if (!unavailableForLineup.contains(player.playerId())) {
                candidates.add(new Candidate(player, lineupPlayer.expectedPoints(), locked));
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
        notes.add("Points are Lock-In values: what each player's week is expected to count with the best "
                + "lock-or-pass choices over their remaining games.");
        if (anyScored) {
            notes.add("Starters who already have a score this week stay in place: a locked score is final, and "
                    + "an unlocked one can still be locked.");
        }
        if (benchPlayed) {
            notes.add("Games played on your bench can't be locked, so bench players count only their remaining games.");
        }
        if (anyPlaying) {
            notes.add("Players in a game right now are locked in place.");
        }
        return new LineupResponse(week, result, ScoringCalculator.round(currentTotal),
                ScoringCalculator.round(optimalTotal), ScoringCalculator.round(optimalTotal - currentTotal),
                changes(result), notes);
    }

    /**
     * Each starter's score this week as Sleeper counts it so far. Before any game, and
     * when the league has no matchup this week, it is empty.
     */
    private Map<String, Double> countedPoints(String leagueId, int week, int rosterId) {
        return sleeperClient.getMatchups(leagueId, week).stream()
                .filter(m -> m.rosterId() == rosterId && m.playersPoints() != null)
                .findFirst().map(SleeperMatchup::playersPoints).orElse(Map.of());
    }

    /** "3 games left", or "Scored 41.5 · 1 game left" for a starter with a score. */
    static String gamesLabel(int gamesLeft, Double score) {
        String left = gamesLeft == 0 ? "no games left" : gamesLeft == 1 ? "1 game left" : gamesLeft + " games left";
        if (score == null) {
            return Character.toUpperCase(left.charAt(0)) + left.substring(1);
        }
        return "Scored %s · %s".formatted(fmt(score), left);
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
            case "Not on an NBA team" -> "not on an NBA team";
            case "No games left" -> "out of games this week";
            case "No games this week" -> "without a game this week";
            default -> "listed as " + availabilityNote;
        };
    }

    private static String name(LineupPlayer p) {
        return Optional.ofNullable(p.player().name()).orElse("Player " + p.player().playerId());
    }

    private static String fmt(double points) {
        return ScoringCalculator.oneDecimal(points);
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list != null ? list : List.of();
    }
}
