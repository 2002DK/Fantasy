package com.fantasy.trade;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import com.fantasy.league.InvalidRequestException;
import com.fantasy.league.NotFoundException;
import com.fantasy.player.PlayerService;
import com.fantasy.player.PlayerSummary;
import com.fantasy.sleeper.NflState;
import com.fantasy.sleeper.SleeperClient;
import com.fantasy.sleeper.SleeperGame;
import com.fantasy.sleeper.SleeperLeague;
import com.fantasy.sleeper.SleeperWeeklyEntry;
import com.fantasy.stats.DefenseTable;
import com.fantasy.stats.Positions;
import com.fantasy.stats.ScoringCalculator;
import com.fantasy.stats.WeeklyDataService;
import com.fantasy.trade.TradeResponse.Side;
import com.fantasy.trade.TradeResponse.Strength;
import com.fantasy.trade.TradeResponse.TradePlayer;
import com.fantasy.trade.TradeResponse.Verdict;
import com.fantasy.trade.TradeResponse.Winner;

/**
 * Evaluates a trade by rest-of-season value above replacement.
 *
 * <p>Rest-of-season points = 70% the sum of weekly projections for the player's
 * remaining games + 30% recent form carried forward over those games, with the
 * form part scaled up to ±15% by remaining schedule strength (projections already
 * reflect each opponent). Value = those points minus a replacement-level player's
 * at the same position, floored at 0.
 */
@Service
public class TradeService {

    static final double PROJECTION_WEIGHT = 0.7;
    static final double FORM_WEIGHT = 0.3;
    static final double MAX_SCHEDULE_ADJUSTMENT = 0.15;
    static final int FORM_GAMES = 3;
    static final int MAX_PLAYERS_PER_SIDE = 5;
    static final double FAIR_MARGIN = 0.10;
    static final double SLIGHT_MARGIN = 0.25;
    /** Last fantasy week when the league has not set its playoff start. */
    static final int DEFAULT_LAST_WEEK = 17;
    static final int RELIABLE_SCHEDULE_WEEKS = 4;
    /** Schedule strength worth calling out in the reasons, in percent. */
    static final double NOTABLE_SCHEDULE_PERCENT = 8;

    /** Statuses that keep a player out for weeks, so recent form should not be carried forward. */
    private static final Set<String> LONG_TERM_ABSENCES = Set.of("IR", "PUP", "Sus", "NA");

    private static final Logger log = LoggerFactory.getLogger(TradeService.class);

    private final SleeperClient sleeperClient;
    private final WeeklyDataService weeklyData;
    private final PlayerService playerService;

    public TradeService(SleeperClient sleeperClient, WeeklyDataService weeklyData, PlayerService playerService) {
        this.sleeperClient = sleeperClient;
        this.weeklyData = weeklyData;
        this.playerService = playerService;
    }

    /** Per-request inputs shared by every player's evaluation. */
    private record Context(
            Map<String, Double> scoring,
            int currentWeek,
            int lastWeek,
            Map<Integer, Map<String, SleeperGame>> gamesByWeekAndTeam,
            Map<Integer, Map<String, SleeperWeeklyEntry>> projectionsByWeek,
            boolean projectionsAvailable,
            List<List<SleeperWeeklyEntry>> pastWeeks,
            DefenseTable defenses,
            ReplacementLevels replacement) {
    }

    public TradeResponse analyze(String leagueId, List<String> giveIds, List<String> getIds) {
        validateSides(giveIds, getIds);
        SleeperLeague league = sleeperClient.getLeague(leagueId)
                .orElseThrow(() -> new NotFoundException("No Sleeper league found with ID '" + leagueId + "'"));
        String season = league.season();
        NflState state = sleeperClient.getNflState();
        if (!season.equals(state.season())) {
            throw new InvalidRequestException(
                    "Trades can only be analyzed for the current season; this league is from " + season);
        }
        int currentWeek = Math.clamp(state.week(), 1, 18);
        int lastWeek = lastFantasyWeek(league.settings());
        if (currentWeek > lastWeek) {
            throw new InvalidRequestException("This league's season is over (it ended in week " + lastWeek + ")");
        }
        List<PlayerSummary> give = giveIds.stream().map(this::findPlayer).toList();
        List<PlayerSummary> get = getIds.stream().map(this::findPlayer).toList();

        List<String> notes = new ArrayList<>();
        Map<String, Double> scoring = league.scoringSettings() != null ? league.scoringSettings() : Map.of();
        Map<Integer, Map<String, SleeperGame>> games = loadGames(season, currentWeek, lastWeek, notes);
        Map<Integer, Map<String, SleeperWeeklyEntry>> projections = loadProjections(season, currentWeek, lastWeek, notes);
        List<List<SleeperWeeklyEntry>> pastWeeks = loadPastWeeks(season, currentWeek, notes);
        DefenseTable defenses = DefenseTable.from(pastWeeks);

        Context partial = new Context(scoring, currentWeek, lastWeek, games, projections, projections != null,
                pastWeeks, defenses, null);
        ReplacementLevels replacement = replacementLevels(league, partial);
        Context context = new Context(scoring, currentWeek, lastWeek, games,
                projections != null ? projections : Map.of(), projections != null, pastWeeks, defenses, replacement);

        Side giveSide = side(give, context);
        Side getSide = side(get, context);
        Verdict verdict = verdict(giveSide, getSide);

        if (league.settings() != null && league.settings().tradeDeadline() > 0
                && currentWeek > league.settings().tradeDeadline()) {
            notes.add("This league's trade deadline (week %d) has passed.".formatted(league.settings().tradeDeadline()));
        }
        if (!pastWeeks.isEmpty() && pastWeeks.size() < RELIABLE_SCHEDULE_WEEKS) {
            notes.add("Schedule strength uses only %s of games so far, so treat it as rough."
                    .formatted(pastWeeks.size() == 1 ? "1 week" : pastWeeks.size() + " weeks"));
        }
        return new TradeResponse(season, currentWeek, lastWeek, verdict, giveSide, getSide,
                reasons(giveSide, getSide, verdict), notes);
    }

    private static void validateSides(List<String> give, List<String> get) {
        if (give.isEmpty() || get.isEmpty()) {
            throw new InvalidRequestException("Pick at least one player on each side of the trade");
        }
        if (give.size() > MAX_PLAYERS_PER_SIDE || get.size() > MAX_PLAYERS_PER_SIDE) {
            throw new InvalidRequestException("A trade can include at most " + MAX_PLAYERS_PER_SIDE + " players per side");
        }
        Set<String> seen = new HashSet<>();
        for (String id : give) {
            if (!seen.add(id)) {
                throw new InvalidRequestException("A player is listed twice in the trade");
            }
        }
        for (String id : get) {
            if (!seen.add(id)) {
                throw new InvalidRequestException("A player is listed twice in the trade");
            }
        }
    }

    /** The final week of the league's playoffs, or week 17 when the league has not set a playoff start. */
    static int lastFantasyWeek(SleeperLeague.Settings settings) {
        if (settings == null || settings.playoffWeekStart() <= 0) {
            return DEFAULT_LAST_WEEK;
        }
        int teams = Math.max(settings.playoffTeams(), 2);
        int rounds = 32 - Integer.numberOfLeadingZeros(teams - 1); // ceil(log2(teams)): 6 teams -> 3 rounds
        return Math.min(settings.playoffWeekStart() + rounds - 1, 18);
    }

    private PlayerSummary findPlayer(String playerId) {
        return playerService.findSummary(playerId)
                .orElseThrow(() -> new NotFoundException("No player found with ID '" + playerId + "'"));
    }

    // --- Data loading: each source may fail independently ---

    private Map<Integer, Map<String, SleeperGame>> loadGames(String season, int from, int to, List<String> notes) {
        try {
            Map<Integer, Map<String, SleeperGame>> byWeek = new HashMap<>();
            for (SleeperGame game : weeklyData.schedule(season)) {
                if (game.week() >= from && game.week() <= to && !game.isCanceled()) {
                    Map<String, SleeperGame> byTeam = byWeek.computeIfAbsent(game.week(), w -> new HashMap<>());
                    byTeam.put(game.home(), game);
                    byTeam.put(game.away(), game);
                }
            }
            return byWeek;
        } catch (RestClientException e) {
            log.warn("Schedule unavailable for {}", season, e);
            notes.add("The NFL schedule is unavailable right now, so byes and schedule strength are not counted.");
            return null;
        }
    }

    private Map<Integer, Map<String, SleeperWeeklyEntry>> loadProjections(String season, int from, int to,
            List<String> notes) {
        try {
            Map<Integer, Map<String, SleeperWeeklyEntry>> byWeek = new HashMap<>();
            weeklyData.projections(season, from, to).forEach((week, entries) -> {
                Map<String, SleeperWeeklyEntry> byPlayer = new HashMap<>();
                entries.forEach(e -> byPlayer.put(e.playerId(), e));
                byWeek.put(week, byPlayer);
            });
            return byWeek;
        } catch (RestClientException e) {
            log.warn("Projections unavailable for {} weeks {}-{}", season, from, to, e);
            notes.add("Projections are unavailable right now, so values use recent form only and positional "
                    + "scarcity is not applied.");
            return null;
        }
    }

    /** Completed weeks, newest first. */
    private List<List<SleeperWeeklyEntry>> loadPastWeeks(String season, int currentWeek, List<String> notes) {
        if (currentWeek == 1) {
            return List.of();
        }
        try {
            List<List<SleeperWeeklyEntry>> weeks = new ArrayList<>();
            for (int w = currentWeek - 1; w >= 1; w--) {
                weeks.add(weeklyData.stats(season, w));
            }
            return weeks;
        } catch (RestClientException e) {
            log.warn("Weekly stats unavailable for {}", season, e);
            notes.add("Past game stats are unavailable right now, so recent form is not counted.");
            return List.of();
        }
    }

    // --- Valuation ---

    /** Rest-of-season projections of every projected player, grouped by position. */
    private ReplacementLevels replacementLevels(SleeperLeague league, Context context) {
        List<String> rosterPositions = league.rosterPositions() != null ? league.rosterPositions() : List.of();
        if (!context.projectionsAvailable()) {
            return ReplacementLevels.compute(rosterPositions, league.totalRosters(), Map.of());
        }
        Set<String> projectedIds = new LinkedHashSet<>();
        context.projectionsByWeek().values().forEach(byPlayer -> projectedIds.addAll(byPlayer.keySet()));
        Map<String, List<Double>> rosByPosition = new HashMap<>();
        playerService.findSummaries(projectedIds).values().forEach(p -> {
            if (p.position() != null) {
                rosByPosition.computeIfAbsent(p.position(), k -> new ArrayList<>())
                        .add(projectedPoints(p, remainingWeeks(p, context), context));
            }
        });
        return ReplacementLevels.compute(rosterPositions, league.totalRosters(), rosByPosition);
    }

    /**
     * Weeks from now through the league's last week in which the player's team plays,
     * skipping byes and this week's game if it has already kicked off.
     */
    private static List<Integer> remainingWeeks(PlayerSummary player, Context context) {
        List<Integer> weeks = new ArrayList<>();
        if (player.team() == null) {
            return weeks;
        }
        for (int week = context.currentWeek(); week <= context.lastWeek(); week++) {
            if (context.gamesByWeekAndTeam() == null) {
                weeks.add(week);
                continue;
            }
            SleeperGame game = context.gamesByWeekAndTeam().getOrDefault(week, Map.of()).get(player.team());
            if (game != null && (week > context.currentWeek() || "pre_game".equals(game.status()))) {
                weeks.add(week);
            }
        }
        return weeks;
    }

    private static double projectedPoints(PlayerSummary player, List<Integer> weeks, Context context) {
        double total = 0;
        for (int week : weeks) {
            SleeperWeeklyEntry entry = context.projectionsByWeek().getOrDefault(week, Map.of()).get(player.playerId());
            if (entry != null) {
                total += ScoringCalculator.points(entry, context.scoring());
            }
        }
        return ScoringCalculator.round(total);
    }

    private TradePlayer evaluate(PlayerSummary player, Context context) {
        List<Integer> weeks = remainingWeeks(player, context);
        int games = weeks.size();
        Double projected = context.projectionsAvailable() ? projectedPoints(player, weeks, context) : null;

        List<Double> recent = recentPoints(player.playerId(), context);
        Double recentAverage = recent.isEmpty() ? null
                : ScoringCalculator.round(recent.stream().mapToDouble(Double::doubleValue).average().orElse(0));
        Double scheduleRatio = scheduleRatio(player, weeks, context);
        double scheduleMultiplier = scheduleRatio == null ? 1.0
                : Math.clamp(scheduleRatio, 1 - MAX_SCHEDULE_ADJUSTMENT, 1 + MAX_SCHEDULE_ADJUSTMENT);

        boolean longTermAbsence = player.injuryStatus() != null && LONG_TERM_ABSENCES.contains(player.injuryStatus());
        Double formPoints = recentAverage != null && !longTermAbsence ? recentAverage * games * scheduleMultiplier : null;
        double restOfSeason;
        if (projected != null && formPoints != null) {
            restOfSeason = PROJECTION_WEIGHT * projected + FORM_WEIGHT * formPoints;
        } else if (projected != null) {
            restOfSeason = projected;
        } else {
            restOfSeason = formPoints != null ? formPoints : 0;
        }

        String note = longTermAbsence ? player.injuryStatus() : null;
        double replacementPoints = context.replacement().level(player.position());
        double value;
        if (!context.replacement().isStartable(player.position())) {
            value = 0;
            note = "No starting slot for " + Optional.ofNullable(player.position()).orElse("this position");
        } else {
            value = Math.max(0, restOfSeason - replacementPoints);
        }
        return new TradePlayer(player, games, projected, recentAverage, recent.size(),
                scheduleRatio == null ? null : ScoringCalculator.round((scheduleRatio - 1) * 100),
                ScoringCalculator.round(restOfSeason), ScoringCalculator.round(replacementPoints),
                ScoringCalculator.round(value), note);
    }

    private static List<Double> recentPoints(String playerId, Context context) {
        List<Double> points = new ArrayList<>();
        for (List<SleeperWeeklyEntry> week : context.pastWeeks()) {
            week.stream()
                    .filter(e -> playerId.equals(e.playerId()) && e.played())
                    .findFirst()
                    .ifPresent(e -> points.add(ScoringCalculator.points(e, context.scoring())));
            if (points.size() == FORM_GAMES) {
                break;
            }
        }
        return points;
    }

    /**
     * Average points the player's remaining opponents allow to their position,
     * relative to the league average (1.10 = 10% easier). Null without data.
     */
    private static Double scheduleRatio(PlayerSummary player, List<Integer> weeks, Context context) {
        if (context.gamesByWeekAndTeam() == null || player.team() == null) {
            return null;
        }
        double ratioSum = 0;
        int counted = 0;
        for (int week : weeks) {
            SleeperGame game = context.gamesByWeekAndTeam().getOrDefault(week, Map.of()).get(player.team());
            if (game == null) {
                continue;
            }
            String opponent = player.team().equals(game.home()) ? game.away() : game.home();
            var standing = context.defenses().standing(opponent, player.position());
            if (standing.isPresent() && standing.get().leagueAverage() > 0) {
                ratioSum += standing.get().allowedPerGame() / standing.get().leagueAverage();
                counted++;
            }
        }
        return counted == 0 ? null : ratioSum / counted;
    }

    private Side side(List<PlayerSummary> players, Context context) {
        List<TradePlayer> evaluated = players.stream().map(p -> evaluate(p, context)).toList();
        return new Side(evaluated,
                ScoringCalculator.round(evaluated.stream().mapToDouble(TradePlayer::restOfSeasonPoints).sum()),
                ScoringCalculator.round(evaluated.stream().mapToDouble(TradePlayer::value).sum()));
    }

    static Verdict verdict(Side give, Side get) {
        double difference = get.totalValue() - give.totalValue();
        double larger = Math.max(get.totalValue(), give.totalValue());
        if (larger <= 0) {
            return new Verdict(null, Strength.FAIR, 0, 0);
        }
        double margin = Math.abs(difference) / larger;
        Strength strength = margin < FAIR_MARGIN ? Strength.FAIR
                : margin < SLIGHT_MARGIN ? Strength.SLIGHT : Strength.CLEAR;
        Winner winner = difference >= 0 ? Winner.YOU : Winner.THEM;
        return new Verdict(winner, strength, ScoringCalculator.round(difference), ScoringCalculator.round(margin * 100));
    }

    // --- Explanation ---

    private static List<String> reasons(Side give, Side get, Verdict verdict) {
        List<String> reasons = new ArrayList<>();
        reasons.add("You receive %s points of rest-of-season value above replacement and give up %s."
                .formatted(fmt(get.totalValue()), fmt(give.totalValue())));

        List<TradePlayer> all = new ArrayList<>(give.players());
        all.addAll(get.players());
        TradePlayer mostValuable = all.stream().max(Comparator.comparingDouble(TradePlayer::value)).orElseThrow();
        if (mostValuable.value() > 0) {
            // Parenthesized: without it, .formatted binds only to the second string literal
            reasons.add(("%s is the most valuable player in the deal: %s projected points over %s, %s above a "
                    + "replacement-level %s.")
                    .formatted(name(mostValuable), fmt(mostValuable.restOfSeasonPoints()),
                            games(mostValuable.remainingGames()), fmt(mostValuable.value()),
                            mostValuable.player().position()));
        }
        TradePlayer mostPoints = all.stream().max(Comparator.comparingDouble(TradePlayer::restOfSeasonPoints)).orElseThrow();
        if (mostPoints != mostValuable && mostPoints.replacementPoints() > 0) {
            reasons.add(("%s scores the most raw points, but a replacement-level %s in this league still scores %s, "
                    + "so their edge over what's available is smaller.")
                    .formatted(name(mostPoints), mostPoints.player().position(), fmt(mostPoints.replacementPoints())));
        }
        for (TradePlayer p : all) {
            Double strength = p.scheduleStrengthPercent();
            if (strength != null && Math.abs(strength) >= NOTABLE_SCHEDULE_PERCENT) {
                reasons.add("%s has one of the %s remaining schedules: opponents allow %s%% %s points to %s than average."
                        .formatted(name(p), strength > 0 ? "easier" : "tougher", "%.0f".formatted(Math.abs(strength)),
                                strength > 0 ? "more" : "fewer", Positions.pluralName(p.player().position())));
            }
        }
        for (TradePlayer p : all) {
            if (p.note() != null && p.note().startsWith("No starting slot")) {
                reasons.add("%s has no starting slot in this league, so they add no trade value.".formatted(name(p)));
            } else if (p.note() != null) {
                reasons.add("%s is on %s, so only projected games after a return count and recent form is ignored."
                        .formatted(name(p), p.note()));
            } else if (p.value() == 0 && p.remainingGames() > 0) {
                reasons.add("%s projects at or below replacement level, so they add little trade value."
                        .formatted(name(p)));
            }
        }
        int extra = get.players().size() - give.players().size();
        if (extra > 0) {
            reasons.add("You receive %d players for %d, so you'll need to open %s."
                    .formatted(get.players().size(), give.players().size(),
                            extra == 1 ? "a roster spot" : extra + " roster spots"));
        } else if (extra < 0) {
            reasons.add("You send %d players for %d, which frees %s."
                    .formatted(give.players().size(), get.players().size(),
                            extra == -1 ? "a roster spot" : -extra + " roster spots"));
        }
        if (verdict.winner() != null && verdict.strength() == Strength.FAIR) {
            reasons.add("The values are within 10%, so this is a fair trade. Roster needs can reasonably decide it.");
        }
        return reasons;
    }

    private static String name(TradePlayer p) {
        return Optional.ofNullable(p.player().name()).orElse("Player " + p.player().playerId());
    }

    private static String fmt(double points) {
        return "%.1f".formatted(points);
    }

    private static String games(int count) {
        return count == 1 ? "1 game" : count + " games";
    }
}
