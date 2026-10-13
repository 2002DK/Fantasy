package com.fantasy.player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import com.fantasy.league.LeagueData;
import com.fantasy.league.NotFoundException;
import com.fantasy.league.SeasonCalendar;
import com.fantasy.player.PlayerDetailResponse.GamePlayed;
import com.fantasy.lockin.BasketballSeason;
import com.fantasy.lockin.BasketballSeasonValuer;
import com.fantasy.lockin.LockInScorer;
import com.fantasy.player.PlayerDetailResponse.Upcoming;
import com.fantasy.player.PlayerDetailResponse.WeekValue;
import com.fantasy.sleeper.SleeperGame;
import com.fantasy.sleeper.SleeperLeague;
import com.fantasy.sleeper.SleeperWeeklyEntry;
import com.fantasy.sleeper.Sport;
import com.fantasy.stats.ScoringCalculator;
import com.fantasy.stats.WeeklyDataService;
import com.fantasy.trade.SeasonValuer;

/** A player's season so far and outlook, scored with one league's settings. */
@Service
public class PlayerDetailService {

    private final LeagueData leagueData;
    private final SeasonCalendar calendar;
    private final SeasonValuer valuer;
    private final PlayerService playerService;
    private final BasketballSeasonValuer basketballValuer;
    private final WeeklyDataService weeklyData;

    public PlayerDetailService(LeagueData leagueData, SeasonCalendar calendar, SeasonValuer valuer,
            PlayerService playerService, BasketballSeasonValuer basketballValuer, WeeklyDataService weeklyData) {
        this.leagueData = leagueData;
        this.calendar = calendar;
        this.valuer = valuer;
        this.playerService = playerService;
        this.basketballValuer = basketballValuer;
        this.weeklyData = weeklyData;
    }

    public PlayerDetailResponse detail(String leagueId, String playerId, String userId) {
        LeagueData.Snapshot snapshot = leagueData.load(leagueId);
        SleeperLeague league = snapshot.league();
        Player entity = playerService.findPlayer(league.sportType(), playerId)
                .orElseThrow(() -> new NotFoundException("No player found with ID '" + playerId + "'"));
        PlayerSummary player = playerService.findSummary(league.sportType(), playerId).orElseThrow();
        int currentWeek = calendar.currentWeek(league);
        int lastWeek = Math.max(currentWeek, SeasonCalendar.lastFantasyWeek(league));
        if (league.sportType() == Sport.NBA) {
            return basketballDetail(snapshot, entity, player, userId, currentWeek, lastWeek);
        }
        SeasonValuer.Season season = valuer.load(league, currentWeek, lastWeek);

        List<GamePlayed> games = new ArrayList<>();
        for (Map<String, SleeperWeeklyEntry> week : season.pastWeeksByPlayer().reversed()) {
            SleeperWeeklyEntry entry = week.get(playerId);
            if (entry != null && entry.played()) {
                games.add(new GamePlayed(entry.week(), entry.opponent(),
                        ScoringCalculator.points(entry, season.scoring())));
            }
        }

        List<Upcoming> upcoming = new ArrayList<>();
        for (int week = currentWeek; week <= lastWeek; week++) {
            SleeperGame game = season.gameOf(player, week);
            if (game == null) {
                upcoming.add(new Upcoming(week, null, null, null, null));
                continue;
            }
            boolean home = player.team().equals(game.home());
            String opponentTeam = home ? game.away() : game.home();
            Double projected = SeasonValuer.projectedPoints(player, week, season);
            var standing = season.defenses().standing(opponentTeam, player.position());
            upcoming.add(new Upcoming(week, (home ? "vs " : "@ ") + opponentTeam,
                    projected != null ? ScoringCalculator.round(projected) : null,
                    standing.map(s -> s.rank()).orElse(null), standing.map(s -> s.teams()).orElse(null)));
        }

        LeagueData.Team owner = snapshot.teamWithPlayer(playerId);
        List<String> notes = new ArrayList<>(season.notes());
        return new PlayerDetailResponse(player, entity.getAge(), entity.getYearsExp(),
                owner != null ? owner.name() : null,
                owner != null && userId != null && owner.roster().isOwnedBy(userId),
                valuer.evaluate(player, season), games, upcoming, notes);
    }

    /**
     * Basketball: the player's recent games (the box scores kept for form, about four
     * weeks), each game left this week and next with its matchup, and every remaining
     * week's Lock-In value.
     */
    private PlayerDetailResponse basketballDetail(LeagueData.Snapshot snapshot, Player entity, PlayerSummary player,
            String userId, int currentWeek, int lastWeek) {
        SleeperLeague league = snapshot.league();
        BasketballSeason season = basketballValuer.load(league, currentWeek, lastWeek);
        List<String> notes = new ArrayList<>(season.notes());
        Map<String, Double> scoring = league.scoringSettings() != null ? league.scoringSettings() : Map.of();

        List<SleeperWeeklyEntry> played = new ArrayList<>();
        try {
            for (int week = Math.max(1, currentWeek - LockInScorer.PAST_WEEKS); week < currentWeek; week++) {
                weeklyData.stats(Sport.NBA, league.season(), week).stream()
                        .filter(e -> player.playerId().equals(e.playerId()) && e.played()).forEach(played::add);
            }
            weeklyData.liveStats(Sport.NBA, league.season(), currentWeek).stream()
                    .filter(e -> player.playerId().equals(e.playerId())).forEach(played::add);
        } catch (RestClientException e) {
            notes.add("Box scores are unavailable right now, so recent games aren't shown.");
        }
        List<GamePlayed> games = played.stream()
                .sorted(Comparator.comparing(SleeperWeeklyEntry::date, Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(e -> new GamePlayed(e.week(), e.opponent(), ScoringCalculator.points(e, scoring), e.date()))
                .toList();

        List<Upcoming> upcoming = season.upcomingGames(player).stream()
                .map(g -> new Upcoming(g.week(), (g.home() ? "vs " : "@ ") + g.opponent(), g.projected(), g.rank(),
                        g.teams(), g.date()))
                .toList();
        List<WeekValue> weeks = season.weeks(player).stream()
                .map(w -> new WeekValue(w.week(), w.games(), ScoringCalculator.round(w.outcome().value())))
                .toList();

        LeagueData.Team owner = snapshot.teamWithPlayer(player.playerId());
        return new PlayerDetailResponse(player, entity.getAge(), entity.getYearsExp(),
                owner != null ? owner.name() : null,
                owner != null && userId != null && owner.roster().isOwnedBy(userId),
                season.evaluate(player), games, upcoming, notes, weeks);
    }
}
