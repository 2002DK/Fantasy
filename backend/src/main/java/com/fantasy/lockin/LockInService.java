package com.fantasy.lockin;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.fantasy.sleeper.SleeperLeague;
import com.fantasy.sleeper.SleeperWeeklyEntry;
import com.fantasy.sleeper.Sport;
import com.fantasy.stats.ScoringCalculator;
import com.fantasy.stats.WeeklyDataService;

/**
 * Weekly Lock-In values for a basketball league: each player's projected games this
 * week, scored with the league's own settings, and the value of the week under the
 * best lock-or-pass strategy.
 */
@Service
public class LockInService {

    /** One projected game. Dates are the game's local (US) date, as Sleeper lists them. */
    public record Game(String gameId, LocalDate date, String opponent, double projectedPoints) {
    }

    /** A player's games this week, in the order they are played, and their Lock-In outcome. */
    public record PlayerWeek(String playerId, List<Game> games, LockInModel.Outcome outcome) {

        static PlayerWeek of(String playerId, List<Game> games) {
            return new PlayerWeek(playerId, games,
                    LockInModel.evaluate(games.stream().map(Game::projectedPoints).toList()));
        }

        /** The same week from a date on: what is left to play once earlier games are done. */
        public PlayerWeek from(LocalDate date) {
            return of(playerId, games.stream().filter(g -> g.date() == null || !g.date().isBefore(date)).toList());
        }
    }

    private static final Comparator<Game> PLAY_ORDER = Comparator
            .comparing(Game::date, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Game::gameId, Comparator.nullsLast(Comparator.naturalOrder()));

    private final WeeklyDataService weeklyData;

    public LockInService(WeeklyDataService weeklyData) {
        this.weeklyData = weeklyData;
    }

    /**
     * Every player with a projected game in the week, keyed by player ID. Players
     * without a projection (injured, inactive or no games) are left out, which is a
     * weekly value of 0. Download failures propagate for the caller to report.
     */
    public Map<String, PlayerWeek> projectWeek(SleeperLeague league, int week) {
        if (league.sportType() != Sport.NBA) {
            throw new IllegalArgumentException("Lock-In values are for basketball leagues");
        }
        Map<String, Double> scoring = league.scoringSettings() != null ? league.scoringSettings() : Map.of();
        Map<String, Map<String, Game>> gamesByPlayer = new HashMap<>();
        for (SleeperWeeklyEntry entry : weeklyData.projections(Sport.NBA, league.season(), week)) {
            Game game = new Game(entry.gameId(), parseDate(entry.date()), entry.opponent(),
                    ScoringCalculator.points(entry, scoring));
            // Keyed by game so a repeated row can't count a game twice
            String key = entry.gameId() != null ? entry.gameId() : entry.date() + "/" + entry.opponent();
            gamesByPlayer.computeIfAbsent(entry.playerId(), id -> new LinkedHashMap<>()).put(key, game);
        }
        Map<String, PlayerWeek> weeks = new HashMap<>();
        gamesByPlayer.forEach((playerId, games) ->
                weeks.put(playerId, PlayerWeek.of(playerId, games.values().stream().sorted(PLAY_ORDER).toList())));
        return weeks;
    }

    private static LocalDate parseDate(String date) {
        try {
            return date != null ? LocalDate.parse(date) : null;
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
