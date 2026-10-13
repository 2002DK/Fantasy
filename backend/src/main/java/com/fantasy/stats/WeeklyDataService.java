package com.fantasy.stats;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import com.fantasy.sleeper.SleeperGame;
import com.fantasy.sleeper.SleeperStatsClient;
import com.fantasy.sleeper.SleeperWeeklyEntry;
import com.fantasy.sleeper.Sport;

/**
 * Cached access to projections, stats and schedules for each sport. Each response is
 * shared by every league and user, so one download serves all comparisons.
 */
@Service
public class WeeklyDataService {

    private record SeasonWeek(Sport sport, String season, int week) {
    }

    private record SportSeason(Sport sport, String season) {
    }

    static final int MAX_PARALLEL_DOWNLOADS = 4;

    private final SleeperStatsClient statsClient;
    /** Projections are revised through the week as injury news lands. */
    private final TtlCache<SeasonWeek, List<SleeperWeeklyEntry>> projections;
    /** Stats for completed weeks rarely change after stat corrections early in the next week. */
    private final TtlCache<SeasonWeek, List<SleeperWeeklyEntry>> stats;
    /** Season-long per-game projections change slowly. */
    private final TtlCache<SportSeason, List<SleeperWeeklyEntry>> seasonProjections;
    /** This week's games as they finish; kept briefly so lock-or-pass advice follows the games. */
    private final TtlCache<SeasonWeek, List<SleeperWeeklyEntry>> liveStats;
    /** Short-lived: game statuses (pre_game, in_game, complete) drive locks and remaining games. */
    private final TtlCache<SportSeason, List<SleeperGame>> schedules;

    public WeeklyDataService(SleeperStatsClient statsClient, Clock clock) {
        this.statsClient = statsClient;
        this.projections = new TtlCache<>(Duration.ofHours(1), clock);
        this.stats = new TtlCache<>(Duration.ofHours(6), clock);
        this.liveStats = new TtlCache<>(Duration.ofMinutes(5), clock);
        this.seasonProjections = new TtlCache<>(Duration.ofHours(6), clock);
        this.schedules = new TtlCache<>(Duration.ofMinutes(10), clock);
    }

    /**
     * Only players with a projected game are kept: a football week's response lists
     * ~3,300 players but only ~500 have a projection, and the rest are empty rows.
     * Basketball has one entry per projected game.
     */
    public List<SleeperWeeklyEntry> projections(Sport sport, String season, int week) {
        return projections.get(new SeasonWeek(sport, season, week),
                () -> statsClient.getProjections(sport, season, week).stream()
                        .filter(SleeperWeeklyEntry::played).toList());
    }

    /**
     * Projections for weeks {@code from} through {@code to}, keyed by week, fetched in
     * parallel so a cold cache costs a few round trips rather than one per week. Each
     * response is ~2 MB of JSON, so at most {@link #MAX_PARALLEL_DOWNLOADS} are parsed
     * at once to keep memory within a small (512 MB) host.
     */
    public Map<Integer, List<SleeperWeeklyEntry>> projections(Sport sport, String season, int from, int to) {
        Map<Integer, List<SleeperWeeklyEntry>> byWeek = new TreeMap<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(MAX_PARALLEL_DOWNLOADS,
                Thread.ofVirtual().factory())) {
            Map<Integer, Future<List<SleeperWeeklyEntry>>> futures = new TreeMap<>();
            for (int week = from; week <= to; week++) {
                int w = week;
                futures.put(w, executor.submit(() -> projections(sport, season, w)));
            }
            for (var entry : futures.entrySet()) {
                byWeek.put(entry.getKey(), entry.getValue().get());
            }
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RestClientException rce) {
                throw rce;
            }
            throw new IllegalStateException("Loading projections failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while loading projections", e);
        }
        return byWeek;
    }

    public List<SleeperWeeklyEntry> stats(Sport sport, String season, int week) {
        return stats.get(new SeasonWeek(sport, season, week), () -> statsClient.getStats(sport, season, week));
    }

    /** Basketball: each projected player's average game this season; only players projected to play are kept. */
    public List<SleeperWeeklyEntry> seasonProjections(Sport sport, String season) {
        return seasonProjections.get(new SportSeason(sport, season), () -> statsClient
                .getSeasonProjections(sport, season).stream().filter(SleeperWeeklyEntry::played).toList());
    }

    /** Stats for the week in progress, refreshed every few minutes; only games already played are kept. */
    public List<SleeperWeeklyEntry> liveStats(Sport sport, String season, int week) {
        return liveStats.get(new SeasonWeek(sport, season, week), () -> statsClient.getStats(sport, season, week)
                .stream().filter(SleeperWeeklyEntry::played).toList());
    }

    public List<SleeperGame> schedule(Sport sport, String season) {
        return schedules.get(new SportSeason(sport, season), () -> statsClient.getSchedule(sport, season));
    }
}
