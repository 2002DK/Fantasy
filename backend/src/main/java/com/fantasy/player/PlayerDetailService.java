package com.fantasy.player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.fantasy.league.LeagueData;
import com.fantasy.league.NotFoundException;
import com.fantasy.league.SeasonCalendar;
import com.fantasy.player.PlayerDetailResponse.GamePlayed;
import com.fantasy.player.PlayerDetailResponse.Upcoming;
import com.fantasy.sleeper.SleeperGame;
import com.fantasy.sleeper.SleeperLeague;
import com.fantasy.sleeper.SleeperWeeklyEntry;
import com.fantasy.stats.ScoringCalculator;
import com.fantasy.trade.SeasonValuer;

/** A player's season so far and outlook, scored with one league's settings. */
@Service
public class PlayerDetailService {

    private final LeagueData leagueData;
    private final SeasonCalendar calendar;
    private final SeasonValuer valuer;
    private final PlayerService playerService;

    public PlayerDetailService(LeagueData leagueData, SeasonCalendar calendar, SeasonValuer valuer,
            PlayerService playerService) {
        this.leagueData = leagueData;
        this.calendar = calendar;
        this.valuer = valuer;
        this.playerService = playerService;
    }

    public PlayerDetailResponse detail(String leagueId, String playerId, String userId) {
        Player entity = playerService.findPlayer(playerId)
                .orElseThrow(() -> new NotFoundException("No player found with ID '" + playerId + "'"));
        PlayerSummary player = playerService.findSummary(playerId).orElseThrow();
        LeagueData.Snapshot snapshot = leagueData.load(leagueId);
        SleeperLeague league = snapshot.league();
        int currentWeek = calendar.currentWeek(league);
        int lastWeek = Math.max(currentWeek, SeasonCalendar.lastFantasyWeek(league.settings()));
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
}
