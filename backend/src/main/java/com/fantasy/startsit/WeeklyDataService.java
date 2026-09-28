package com.fantasy.startsit;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.springframework.stereotype.Service;

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

    public List<SleeperWeeklyEntry> projections(String season, int week) {
        return projections.get(new SeasonWeek(season, week), () -> statsClient.getProjections(season, week));
    }

    public List<SleeperWeeklyEntry> stats(String season, int week) {
        return stats.get(new SeasonWeek(season, week), () -> statsClient.getStats(season, week));
    }

    public List<SleeperGame> schedule(String season) {
        return schedules.get(season, () -> statsClient.getSchedule(season));
    }
}
