package com.fantasy.standings;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import com.fantasy.league.LeagueData;
import com.fantasy.league.SeasonCalendar;
import com.fantasy.lineup.LineupSolver;
import com.fantasy.lineup.TeamProjector;
import com.fantasy.lockin.BasketballSeason;
import com.fantasy.lockin.BasketballSeasonValuer;
import com.fantasy.player.PlayerSummary;
import com.fantasy.sleeper.SleeperClient;
import com.fantasy.sleeper.SleeperLeague;
import com.fantasy.sleeper.SleeperMatchup;
import com.fantasy.sleeper.SleeperRoster;
import com.fantasy.sleeper.Sport;
import com.fantasy.standings.SeasonSimulator.TeamStart;
import com.fantasy.standings.SeasonSimulator.WeekGames;
import com.fantasy.standings.StandingsResponse.TeamStanding;
import com.fantasy.stats.ScoringCalculator;
import com.fantasy.stats.TtlCache;
import com.fantasy.trade.SeasonValuer;

/**
 * Standings, power rankings and playoff odds. A team's strength is its expected best
 * starting lineup per week for the rest of the season, from weekly projections (so
 * byes and injuries count). Playoff odds simulate the remaining regular season with
 * Sleeper's actual pairings.
 */
@Service
public class StandingsService {

    static final int SIMULATIONS = 10_000;
    static final int DEFAULT_PLAYOFF_TEAMS = 6;
    /** Concurrent Sleeper calls when fetching a season's pairings. */
    static final int MAX_PARALLEL_REQUESTS = 6;

    private static final Logger log = LoggerFactory.getLogger(StandingsService.class);

    private record LeagueWeek(String leagueId, int week) {
    }

    private final LeagueData leagueData;
    private final SeasonCalendar calendar;
    private final SeasonValuer valuer;
    private final SleeperClient sleeperClient;
    private final BasketballSeasonValuer basketballValuer;
    /** Future pairings rarely change; they are fetched once per league and week for half an hour. */
    private final TtlCache<LeagueWeek, List<SleeperMatchup>> pairings;

    public StandingsService(LeagueData leagueData, SeasonCalendar calendar, SeasonValuer valuer,
            SleeperClient sleeperClient, BasketballSeasonValuer basketballValuer, Clock clock) {
        this.leagueData = leagueData;
        this.calendar = calendar;
        this.valuer = valuer;
        this.sleeperClient = sleeperClient;
        this.basketballValuer = basketballValuer;
        this.pairings = new TtlCache<>(Duration.ofMinutes(30), clock);
    }

    public StandingsResponse standings(String leagueId, String userId) {
        LeagueData.Snapshot snapshot = leagueData.load(leagueId);
        SleeperLeague league = snapshot.league();
        int currentWeek = calendar.currentWeek(league);
        int regularSeasonEnd = SeasonCalendar.regularSeasonEnd(league);
        int lastWeek = SeasonCalendar.lastFantasyWeek(league);
        int playoffTeams = league.settings() != null && league.settings().playoffTeams() > 0
                ? league.settings().playoffTeams() : DEFAULT_PLAYOFF_TEAMS;

        List<String> notes = new ArrayList<>();
        Map<Integer, Double> strength = new HashMap<>();
        SeasonSimulator.Result odds = null;

        if (currentWeek <= lastWeek) {
            boolean basketball = league.sportType() == Sport.NBA;
            SeasonValuer.Season season = basketball ? null : valuer.load(league, currentWeek, lastWeek);
            BasketballSeason basketballSeason = basketball ? basketballValuer.load(league, currentWeek, lastWeek) : null;
            notes.addAll(basketball ? basketballSeason.notes() : season.notes());
            if (basketball ? basketballSeason.projectionsAvailable() : season.projectionsAvailable()) {
                List<String> slots = LineupSolver.startingSlots(snapshot.rosterPositions());
                Map<Integer, List<PlayerSummary>> lineupPlayers = new HashMap<>();
                List<PlayerSummary> everyone = new ArrayList<>();
                for (LeagueData.Team team : snapshot.teams()) {
                    List<PlayerSummary> players = startable(team);
                    lineupPlayers.put(team.rosterId(), players);
                    everyone.addAll(players);
                }
                TeamProjector projector = basketball
                        ? new TeamProjector(slots, currentWeek, lastWeek, everyone, basketballSeason::project)
                        : new TeamProjector(slots, season, currentWeek, lastWeek, everyone);
                for (LeagueData.Team team : snapshot.teams()) {
                    strength.put(team.rosterId(),
                            ScoringCalculator.round(projector.restOfSeason(lineupPlayers.get(team.rosterId()))
                                    / projector.weeks()));
                }
                if (currentWeek <= regularSeasonEnd) {
                    odds = simulate(leagueId, snapshot, projector, lineupPlayers, currentWeek, regularSeasonEnd,
                            playoffTeams, notes);
                }
            }
        }
        if (currentWeek > regularSeasonEnd) {
            notes.add("The regular season is over, so playoff odds are no longer simulated.");
        } else if (!SeasonCalendar.playoffStartIsSet(league.settings())) {
            notes.add("This league hasn't set a playoff start week; odds assume the regular season ends in week %d."
                    .formatted(regularSeasonEnd));
        }
        if (odds != null) {
            notes.add("Odds come from %,d simulations of the remaining schedule using each team's best projected lineup; "
                    .formatted(SIMULATIONS) + "tiebreakers use points for, and divisions aren't modelled.");
            if (league.sportType() == Sport.NBA) {
                notes.add("Basketball strength counts one game per starter each week, at its Lock-In value.");
            }
        }
        return new StandingsResponse(currentWeek, regularSeasonEnd, playoffTeams, odds != null ? SIMULATIONS : null,
                rows(snapshot, strength, odds, userId), notes);
    }

    /** Taxi-squad players can't start; injured-reserve players can once they return, which projections reflect. */
    private static List<PlayerSummary> startable(LeagueData.Team team) {
        Set<String> taxi = new HashSet<>(team.roster().taxi() != null ? team.roster().taxi() : List.of());
        return team.players().stream().filter(Objects::nonNull).filter(p -> !taxi.contains(p.playerId())).toList();
    }

    private SeasonSimulator.Result simulate(String leagueId, LeagueData.Snapshot snapshot, TeamProjector projector,
            Map<Integer, List<PlayerSummary>> lineupPlayers, int fromWeek, int toWeek, int playoffTeams,
            List<String> notes) {
        List<WeekGames> weeks = new ArrayList<>();
        try {
            Map<Integer, List<SleeperMatchup>> matchupsByWeek = loadPairings(leagueId, fromWeek, toWeek);
            for (int week = fromWeek; week <= toWeek; week++) {
                int w = week;
                List<SleeperMatchup> matchups = matchupsByWeek.get(w);
                Map<Integer, Double> means = new HashMap<>();
                Map<Integer, Double> variances = new HashMap<>();
                lineupPlayers.forEach((rosterId, players) -> {
                    TeamProjector.WeekProjection projection = projector.week(players, w);
                    means.put(rosterId, projection.mean());
                    variances.put(rosterId, projection.variance());
                });
                weeks.add(new WeekGames(pairs(matchups), means, variances));
            }
        } catch (RestClientException e) {
            log.warn("League schedule unavailable for {}", leagueId, e);
            notes.add("The league schedule is unavailable right now, so playoff odds aren't simulated.");
            return null;
        }
        List<TeamStart> starts = snapshot.teams().stream().map(t -> {
            SleeperRoster.Settings s = t.roster().settings();
            double wins = s == null ? 0 : s.wins() + 0.5 * s.ties();
            return new TeamStart(t.rosterId(), wins, pointsFor(s));
        }).toList();
        boolean median = snapshot.league().settings() != null && snapshot.league().settings().playsMedian();
        long seed = (leagueId + ":" + fromWeek).hashCode();
        return SeasonSimulator.simulate(starts, weeks, playoffTeams, median, SIMULATIONS, seed);
    }

    /**
     * Each remaining week's pairings, fetched a few at a time: a basketball season has
     * about 20 regular-season weeks, and one call after another made the first visit slow.
     */
    private Map<Integer, List<SleeperMatchup>> loadPairings(String leagueId, int fromWeek, int toWeek) {
        Map<Integer, List<SleeperMatchup>> byWeek = new TreeMap<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(MAX_PARALLEL_REQUESTS,
                Thread.ofVirtual().factory())) {
            Map<Integer, Future<List<SleeperMatchup>>> futures = new TreeMap<>();
            for (int week = fromWeek; week <= toWeek; week++) {
                int w = week;
                futures.put(w, executor.submit(() -> pairings.get(new LeagueWeek(leagueId, w),
                        () -> sleeperClient.getMatchups(leagueId, w))));
            }
            for (var entry : futures.entrySet()) {
                byWeek.put(entry.getKey(), entry.getValue().get());
            }
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RestClientException rce) {
                throw rce;
            }
            throw new IllegalStateException("Loading the league schedule failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while loading the league schedule", e);
        }
        return byWeek;
    }

    /** Teams sharing a matchup ID play each other. */
    static List<int[]> pairs(List<SleeperMatchup> matchups) {
        Map<Integer, List<Integer>> byMatchup = new HashMap<>();
        for (SleeperMatchup m : matchups) {
            if (m.matchupId() != null) {
                byMatchup.computeIfAbsent(m.matchupId(), k -> new ArrayList<>()).add(m.rosterId());
            }
        }
        return byMatchup.values().stream().filter(ids -> ids.size() == 2)
                .map(ids -> new int[] {ids.get(0), ids.get(1)}).toList();
    }

    private static double pointsFor(SleeperRoster.Settings s) {
        return s == null ? 0 : s.fpts() + s.fptsDecimal() / 100.0;
    }

    private static List<TeamStanding> rows(LeagueData.Snapshot snapshot, Map<Integer, Double> strength,
            SeasonSimulator.Result odds, String userId) {
        List<LeagueData.Team> byStrength = snapshot.teams().stream()
                .sorted(Comparator.comparingDouble((LeagueData.Team t) -> strength.getOrDefault(t.rosterId(), 0.0))
                        .reversed())
                .toList();
        List<LeagueData.Team> byRecord = snapshot.teams().stream()
                .sorted(Comparator.comparingDouble((LeagueData.Team t) -> winsOf(t)).reversed()
                        .thenComparing(Comparator.comparingDouble((LeagueData.Team t) -> pointsFor(t.roster().settings()))
                                .reversed()))
                .toList();
        List<TeamStanding> rows = new ArrayList<>();
        for (int i = 0; i < byRecord.size(); i++) {
            LeagueData.Team t = byRecord.get(i);
            SleeperRoster.Settings s = t.roster().settings();
            rows.add(new TeamStanding(i + 1, t.rosterId(), t.name(), t.owner().displayName(), t.owner().avatarUrl(),
                    s == null ? 0 : s.wins(), s == null ? 0 : s.losses(), s == null ? 0 : s.ties(),
                    ScoringCalculator.round(pointsFor(s)), strength.getOrDefault(t.rosterId(), 0.0),
                    byStrength.indexOf(t) + 1,
                    odds == null ? null : ScoringCalculator.round(odds.playoffOdds().get(t.rosterId()) * 100),
                    odds == null ? null : ScoringCalculator.round(odds.averageWins().get(t.rosterId())),
                    userId != null && t.roster().isOwnedBy(userId)));
        }
        return rows;
    }

    private static double winsOf(LeagueData.Team t) {
        SleeperRoster.Settings s = t.roster().settings();
        return s == null ? 0 : s.wins() + 0.5 * s.ties();
    }
}
