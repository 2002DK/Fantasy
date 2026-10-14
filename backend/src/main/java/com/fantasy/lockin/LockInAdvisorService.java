package com.fantasy.lockin;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fantasy.league.InvalidRequestException;
import com.fantasy.league.LeagueData;
import com.fantasy.league.SeasonCalendar;
import com.fantasy.lineup.LineupSolver;
import com.fantasy.lockin.LockInAdviceResponse.Action;
import com.fantasy.lockin.LockInAdviceResponse.PlayedGame;
import com.fantasy.lockin.LockInAdviceResponse.StarterAdvice;
import com.fantasy.lockin.LockInAdviceResponse.UpcomingGame;
import com.fantasy.player.PlayerSummary;
import com.fantasy.sleeper.SleeperClient;
import com.fantasy.sleeper.SleeperLeague;
import com.fantasy.sleeper.SleeperMatchup;
import com.fantasy.sleeper.Sport;
import com.fantasy.stats.ScoringCalculator;

/**
 * The lock-or-pass decision in a Lock-In league: after a starter plays, lock the score
 * if it beats the Lock-In value of their remaining games, and pass otherwise. Sleeper's
 * API doesn't say which scores are locked, so users mark their locks here; marked
 * scores count as final in the lineup optimizer and matchup preview.
 */
@Service
public class LockInAdvisorService {

    private final LeagueData leagueData;
    private final SeasonCalendar calendar;
    private final LockInScorer scorer;
    private final LockInMarkRepository marks;
    private final SleeperClient sleeperClient;
    private final Clock clock;

    public LockInAdvisorService(LeagueData leagueData, SeasonCalendar calendar, LockInScorer scorer,
            SleeperClient sleeperClient, LockInMarkRepository marks, Clock clock) {
        this.leagueData = leagueData;
        this.calendar = calendar;
        this.scorer = scorer;
        this.sleeperClient = sleeperClient;
        this.marks = marks;
        this.clock = clock;
    }

    /** Each starter's score as Sleeper counts it this week; empty before any game or without a matchup. */
    private Map<String, Double> countedPoints(String leagueId, int week, int rosterId) {
        return sleeperClient.getMatchups(leagueId, week).stream()
                .filter(m -> m.rosterId() == rosterId && m.playersPoints() != null)
                .findFirst().map(SleeperMatchup::playersPoints).orElse(Map.of());
    }

    public LockInAdviceResponse advice(String leagueId, String userId) {
        Context context = context(leagueId, userId);
        LockInScorer.Week week = scorer.load(context.league(), context.week());
        List<String> notes = new ArrayList<>(week.notes());
        Map<String, LockInMark> marked = marked(leagueId, userId, context.week());
        Map<String, Double> counted = countedPoints(leagueId, context.week(), context.team().rosterId());

        List<String> slots = LineupSolver.startingSlots(context.snapshot().rosterPositions());
        List<String> starterIds = context.team().roster().starters() != null ? context.team().roster().starters()
                : List.of();
        List<StarterAdvice> starters = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            String id = i < starterIds.size() ? starterIds.get(i) : null;
            PlayerSummary player = id == null ? null : context.team().players().stream()
                    .filter(p -> p.playerId().equals(id)).findFirst().orElse(null);
            if (player != null) {
                starters.add(advise(slots.get(i), player, week, week.playedGames(id), marked.get(id),
                        week.inferredLock(id, counted.get(id))));
            }
        }
        notes.add("Only games played while in your starting lineup can be locked. When Sleeper counts an earlier "
                + "game than the latest, that score must be locked, and it's shown here automatically; otherwise, "
                + "mark a lock here after making it in Sleeper so the lineup and matchup count it as final.");
        return new LockInAdviceResponse(context.week(), starters, notes);
    }

    /** Marks the starter's latest finished game this week as locked. */
    @Transactional
    public LockInAdviceResponse lock(String leagueId, String userId, String playerId) {
        Context context = context(leagueId, userId);
        PlayerSummary player = starter(context, playerId);
        LockInScorer.Week week = scorer.load(context.league(), context.week());
        if (week.isPlaying(player)) {
            throw new InvalidRequestException(name(player) + " is playing right now; lock the score after the game.");
        }
        List<PlayedGame> games = week.playedGames(playerId);
        if (games.isEmpty()) {
            throw new InvalidRequestException(name(player) + " hasn't finished a game this week yet.");
        }
        PlayedGame latest = games.getLast();
        marks.save(new LockInMark(leagueId, userId, context.week(), playerId, latest.gameId(), latest.date(),
                latest.opponent(), latest.points(), clock.instant()));
        return advice(leagueId, userId);
    }

    @Transactional
    public LockInAdviceResponse unlock(String leagueId, String userId, String playerId) {
        Context context = context(leagueId, userId);
        marks.deleteById(new LockInMark.Key(leagueId, userId, context.week(), playerId));
        return advice(leagueId, userId);
    }

    /** The user's marked locks this week, keyed by player ID; used by the lineup and matchup tools. */
    public Map<String, LockInMark> marked(String leagueId, String userId, int week) {
        Map<String, LockInMark> byPlayer = new HashMap<>();
        marks.findByLeagueIdAndUserIdAndWeek(leagueId, userId, week).forEach(m -> byPlayer.put(m.getPlayerId(), m));
        return byPlayer;
    }

    /**
     * One starter's call. The latest finished game can be locked until the next one
     * starts; it is worth locking when it beats the Lock-In value of the games left.
     */
    StarterAdvice advise(String slot, PlayerSummary player, LockInScorer.Week week, List<PlayedGame> played,
            LockInMark mark, PlayedGame inferred) {
        LockInScorer.Analysis analysis = scorer.analyze(player, week);
        List<UpcomingGame> upcoming = upcoming(analysis);
        Double waitValue = upcoming.isEmpty() ? null : ScoringCalculator.round(analysis.value());
        PlayedGame latest = played.isEmpty() ? null : played.getLast();
        boolean playing = week.isPlaying(player);
        String name = name(player);

        if (mark != null) {
            PlayedGame locked = new PlayedGame(mark.getGameId(), mark.getGameDate(), mark.getOpponent(),
                    mark.getPoints());
            return new StarterAdvice(slot, player, Action.LOCKED,
                    "Locked in at %s.".formatted(fmt(mark.getPoints())), latest, waitValue, upcoming, locked, false,
                    true);
        }
        if (inferred != null) {
            return new StarterAdvice(slot, player, Action.LOCKED,
                    "Locked in at %s: Sleeper is counting an earlier game than their latest."
                            .formatted(fmt(inferred.points())),
                    latest, waitValue, upcoming, inferred, false, false);
        }
        if (playing) {
            String after = upcoming.size() > 1
                    ? "Lock the score after this game if it's %s or more.".formatted(fmt(upcoming.getFirst().lockAbove()))
                    : "It's their last game this week, so it counts unless you locked an earlier score.";
            return new StarterAdvice(slot, player, Action.PLAYING, name + " is playing now. " + after, latest,
                    waitValue, upcoming, null, false, false);
        }
        if (latest != null && waitValue != null) {
            String games = upcoming.size() == 1 ? "1 game" : upcoming.size() + " games";
            if (latest.points() >= waitValue) {
                return new StarterAdvice(slot, player, Action.LOCK,
                        "Scored %s with %s left. Lock it in: waiting is worth %s."
                                .formatted(fmt(latest.points()), games, fmt(waitValue)),
                        latest, waitValue, upcoming, null, true, false);
            }
            return new StarterAdvice(slot, player, Action.PASS,
                    "Scored %s with %s left. Pass: waiting is worth %s."
                            .formatted(fmt(latest.points()), games, fmt(waitValue)),
                    latest, waitValue, upcoming, null, true, false);
        }
        if (latest != null) {
            return new StarterAdvice(slot, player, Action.LAST_GAME,
                    "Scored %s in their last game this week, which counts unless you locked an earlier score."
                            .formatted(fmt(latest.points())),
                    latest, null, upcoming, null, true, false);
        }
        if (!upcoming.isEmpty()) {
            String message = upcoming.size() == 1
                    ? "One game this week, so it counts whatever the score."
                    : "%d games this week. Lock their first score if it's %s or more."
                            .formatted(upcoming.size(), fmt(upcoming.getFirst().lockAbove()));
            return new StarterAdvice(slot, player, Action.WAITING, message, null, waitValue, upcoming, null, false,
                    false);
        }
        String reason = switch (Optional.ofNullable(analysis.availabilityNote()).orElse("")) {
            case "IR" -> name + " is on injured reserve.";
            case "Not on an NBA team" -> name + " is not on an NBA team.";
            case "Out" -> name + " is out with no other games this week.";
            default -> name + " has no games this week.";
        };
        return new StarterAdvice(slot, player, Action.NO_GAMES, reason, null, null, upcoming, null, false,
                false);
    }

    private static List<UpcomingGame> upcoming(LockInScorer.Analysis analysis) {
        List<UpcomingGame> games = new ArrayList<>();
        List<Double> thresholds = analysis.outcome().thresholds();
        for (int i = 0; i < analysis.games().size(); i++) {
            LockInScorer.GameOutlook game = analysis.games().get(i);
            boolean last = i == analysis.games().size() - 1;
            games.add(new UpcomingGame(game.date(), game.opponent(), game.home(), game.expectedPoints(),
                    last ? null : ScoringCalculator.round(thresholds.get(i))));
        }
        return games;
    }

    private static String fmt(double points) {
        return ScoringCalculator.oneDecimal(points);
    }

    // --- Helpers ---

    private record Context(SleeperLeague league, LeagueData.Snapshot snapshot, LeagueData.Team team, int week) {
    }

    private Context context(String leagueId, String userId) {
        LeagueData.Snapshot snapshot = leagueData.load(leagueId);
        if (snapshot.league().sportType() != Sport.NBA) {
            throw new InvalidRequestException("Lock-or-pass advice is for basketball Lock-In leagues.");
        }
        LeagueData.Team team = snapshot.teamOf(userId);
        return new Context(snapshot.league(), snapshot, team, calendar.currentWeek(snapshot.league()));
    }

    private static PlayerSummary starter(Context context, String playerId) {
        List<String> starters = context.team().roster().starters();
        if (starters == null || !starters.contains(playerId)) {
            throw new InvalidRequestException("Only your current starters can be locked.");
        }
        return context.team().players().stream().filter(p -> p.playerId().equals(playerId)).findFirst()
                .orElseThrow(() -> new InvalidRequestException("Only your current starters can be locked."));
    }

    private static String name(PlayerSummary player) {
        return Optional.ofNullable(player.name()).orElse("Player " + player.playerId());
    }
}
