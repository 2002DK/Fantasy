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

/**
 * Cached access to weekly projections, stats and the schedule. Each response is
 * shared by every league and user, so one download serves all comparisons.
 */
@Service
public class WeeklyDataService {

    private record SeasonWeek(String season, int week) {
    }

    static final int MAX_PARALLEL_DOWNLOADS = 4;

    private final SleeperStatsClient statsClient;
    /** Projections are revised through the week as injury news lands. */
    private final TtlCache<SeasonWeek, List<SleeperWeeklyEntry>> projections;
    /** Stats for completed weeks rarely change after stat corrections early in the next week. */
    private final TtlCache<SeasonWeek, List<SleeperWeeklyEntry>> stats;
    private final TtlCache<String, List<SleeperGame>> schedules;

    public WeeklyDataService(SleeperStatsClient statsClient, Clock clock) {
        this.statsClient = statsClient;
        this.projections = new TtlCache<>(Duration.ofHours(1), clock);
        this.stats = new TtlCache<>(Duration.ofHours(6), clock);
        this.schedules = new TtlCache<>(Duration.ofHours(6), clock);
    }

    /**
     * Only players with a projected game are kept: a week's response lists ~3,300
     * players but only ~500 have a projection, and the rest are empty rows.
     */
    public List<SleeperWeeklyEntry> projections(String season, int week) {
        return projections.get(new SeasonWeek(season, week),
                () -> statsClient.getProjections(season, week).stream().filter(SleeperWeeklyEntry::played).toList());
    }

    /**
     * Projections for weeks {@code from} through {@code to}, keyed by week, fetched in
     * parallel so a cold cache costs a few round trips rather than one per week. Each
     * response is ~2 MB of JSON, so at most {@link #MAX_PARALLEL_DOWNLOADS} are parsed
     * at once to keep memory within a small (512 MB) host.
     */
    public Map<Integer, List<SleeperWeeklyEntry>> projections(String season, int from, int to) {
        Map<Integer, List<SleeperWeeklyEntry>> byWeek = new TreeMap<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(MAX_PARALLEL_DOWNLOADS,
                Thread.ofVirtual().factory())) {
            Map<Integer, Future<List<SleeperWeeklyEntry>>> futures = new TreeMap<>();
            for (int week = from; week <= to; week++) {
                int w = week;
                futures.put(w, executor.submit(() -> projections(season, w)));
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

    public List<SleeperWeeklyEntry> stats(String season, int week) {
        return stats.get(new SeasonWeek(season, week), () -> statsClient.getStats(season, week));
    }

    public List<SleeperGame> schedule(String season) {
        return schedules.get(season, () -> statsClient.getSchedule(season));
    }
}
