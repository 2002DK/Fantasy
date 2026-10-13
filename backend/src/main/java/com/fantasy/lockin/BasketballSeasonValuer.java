package com.fantasy.lockin;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import com.fantasy.lockin.LockInScorer.ScheduledGame;
import com.fantasy.lockin.LockInService.PlayerWeek;
import com.fantasy.player.PlayerService;
import com.fantasy.player.PlayerSummary;
import com.fantasy.sleeper.SleeperGame;
import com.fantasy.sleeper.SleeperLeague;
import com.fantasy.sleeper.SleeperWeeklyEntry;
import com.fantasy.sleeper.Sport;
import com.fantasy.stats.ScoringCalculator;
import com.fantasy.stats.WeeklyDataService;
import com.fantasy.trade.ReplacementLevels;

/**
 * Loads a basketball league's {@link BasketballSeason}: this week's Lock-In data, the
 * schedule through the last fantasy week, next week's per-game projections and
 * season-long per-game projections, each of which may fail on its own. Replacement
 * levels come from the rest-of-season value of every projected player.
 */
@Component
public class BasketballSeasonValuer {

    /** 30 teams × 82 games ÷ 2. */
    static final int REGULAR_SEASON_GAMES = 1230;

    private static final Logger log = LoggerFactory.getLogger(BasketballSeasonValuer.class);

    private final LockInScorer scorer;
    private final LockInService lockInService;
    private final WeeklyDataService weeklyData;
    private final PlayerService playerService;

    public BasketballSeasonValuer(LockInScorer scorer, LockInService lockInService, WeeklyDataService weeklyData,
            PlayerService playerService) {
        this.scorer = scorer;
        this.lockInService = lockInService;
        this.weeklyData = weeklyData;
        this.playerService = playerService;
    }

    public BasketballSeason load(SleeperLeague league, int currentWeek, int lastWeek) {
        LockInScorer.Week current = scorer.load(league, currentWeek);
        List<String> notes = new ArrayList<>(current.notes());
        Map<Integer, Map<String, List<ScheduledGame>>> schedule = loadSchedule(league.season(), currentWeek, lastWeek,
                notes);
        Map<String, PlayerWeek> nextWeek = Map.of();
        if (currentWeek < lastWeek) {
            try {
                nextWeek = lockInService.projectWeek(league, currentWeek + 1);
            } catch (RestClientException e) {
                log.warn("Next week's basketball projections unavailable", e);
            }
        }
        Map<String, Double> perGame = loadPerGameProjections(league, notes);
        BasketballSeason season = new BasketballSeason(scorer, current, currentWeek, lastWeek, schedule, nextWeek,
                perGame, notes);
        season.setReplacement(replacementLevels(league, season, perGame.keySet()));
        return season;
    }

    private Map<Integer, Map<String, List<ScheduledGame>>> loadSchedule(String season, int from, int to,
            List<String> notes) {
        try {
            Map<Integer, Map<String, List<ScheduledGame>>> byWeek = new HashMap<>();
            List<SleeperGame> allGames = weeklyData.schedule(Sport.NBA, season);
            long scheduled = allGames.stream().filter(g -> !g.isCanceled()).count();
            if (scheduled > 0 && scheduled < REGULAR_SEASON_GAMES) {
                // The NBA Cup's December games for teams out of the knockout round are set mid-season
                notes.add(("%d NBA games aren't scheduled yet (the NBA Cup's December games), so the weeks around "
                        + "them may get more games than shown.").formatted(REGULAR_SEASON_GAMES - scheduled));
            }
            for (SleeperGame game : allGames) {
                if (game.week() < from || game.week() > to || game.isCanceled() || game.home() == null
                        || game.away() == null) {
                    continue;
                }
                Map<String, List<ScheduledGame>> byTeam = byWeek.computeIfAbsent(game.week(), w -> new HashMap<>());
                LocalDate date = parseDate(game.date());
                byTeam.computeIfAbsent(game.home(), t -> new ArrayList<>())
                        .add(new ScheduledGame(game.gameId(), date, game.away(), true, game.status()));
                byTeam.computeIfAbsent(game.away(), t -> new ArrayList<>())
                        .add(new ScheduledGame(game.gameId(), date, game.home(), false, game.status()));
            }
            Comparator<ScheduledGame> byDate = Comparator.comparing(ScheduledGame::date,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            byWeek.values().forEach(byTeam -> byTeam.values().forEach(games -> games.sort(byDate)));
            return byWeek;
        } catch (RestClientException e) {
            log.warn("Basketball schedule unavailable for {}", season, e);
            notes.add("The NBA schedule is unavailable right now, so only this week's games are counted.");
            return null;
        }
    }

    private Map<String, Double> loadPerGameProjections(SleeperLeague league, List<String> notes) {
        Map<String, Double> scoring = league.scoringSettings() != null ? league.scoringSettings() : Map.of();
        try {
            Map<String, Double> perGame = new HashMap<>();
            for (SleeperWeeklyEntry entry : weeklyData.seasonProjections(Sport.NBA, league.season())) {
                perGame.put(entry.playerId(), ScoringCalculator.points(entry, scoring));
            }
            return perGame;
        } catch (RestClientException e) {
            log.warn("Basketball season projections unavailable for {}", league.season(), e);
            notes.add("Season projections are unavailable right now, so later weeks use recent form only and "
                    + "positional scarcity is not applied.");
            return Map.of();
        }
    }

    private static LocalDate parseDate(String date) {
        try {
            return date != null ? LocalDate.parse(date) : null;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Ranks every projected player's rest-of-season value by primary position. */
    private ReplacementLevels replacementLevels(SleeperLeague league, BasketballSeason season,
            Set<String> projectedIds) {
        List<String> rosterPositions = league.rosterPositions() != null ? league.rosterPositions() : List.of();
        Map<String, List<Double>> byPosition = new HashMap<>();
        for (PlayerSummary player : playerService.findSummaries(Sport.NBA, new LinkedHashSet<>(projectedIds))
                .values()) {
            if (player.position() != null && player.name() != null) {
                byPosition.computeIfAbsent(player.position(), p -> new ArrayList<>()).add(season.restOfSeason(player));
            }
        }
        byPosition.values().forEach(values -> values.sort(Comparator.reverseOrder()));
        return ReplacementLevels.compute(rosterPositions, league.totalRosters(), byPosition);
    }
}
