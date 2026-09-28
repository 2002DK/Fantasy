package com.fantasy.league;

import static com.fantasy.sleeper.SleeperAvatars.thumbUrl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.fantasy.player.PlayerService;
import com.fantasy.player.PlayerSummary;
import com.fantasy.sleeper.SleeperClient;
import com.fantasy.sleeper.SleeperLeague;
import com.fantasy.sleeper.SleeperLeagueUser;
import com.fantasy.sleeper.SleeperRoster;

@Service
public class RosterService {

    private static final String BENCH_SLOT = "BN";
    private static final String EMPTY_SLOT_PLAYER_ID = "0";

    private final SleeperClient sleeperClient;
    private final PlayerService playerService;

    public RosterService(SleeperClient sleeperClient, PlayerService playerService) {
        this.sleeperClient = sleeperClient;
        this.playerService = playerService;
    }

    /** Finds the roster the user owns or co-owns in the league. */
    public RosterResponse findRoster(String leagueId, String userId) {
        SleeperLeague league = sleeperClient.getLeague(leagueId)
                .orElseThrow(() -> new NotFoundException("No Sleeper league found with ID '" + leagueId + "'"));
        SleeperRoster roster = sleeperClient.getRosters(leagueId).stream()
                .filter(r -> r.isOwnedBy(userId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException(
                        "User '" + userId + "' has no roster in league '" + league.name() + "'"));
        SleeperLeagueUser owner = sleeperClient.getLeagueUsers(leagueId).stream()
                .filter(u -> u.userId().equals(roster.ownerId()))
                .findFirst()
                .orElse(null);

        List<String> starterIds = orEmpty(roster.starters());
        List<String> reserveIds = orEmpty(roster.reserve());
        List<String> taxiIds = orEmpty(roster.taxi());
        List<String> benchIds = bench(orEmpty(roster.players()), starterIds, reserveIds, taxiIds);

        Set<String> allIds = new LinkedHashSet<>(starterIds);
        allIds.addAll(benchIds);
        allIds.addAll(reserveIds);
        allIds.addAll(taxiIds);
        allIds.remove(EMPTY_SLOT_PLAYER_ID);
        Map<String, PlayerSummary> players = playerService.findSummaries(allIds);

        return new RosterResponse(
                league.leagueId(),
                league.name(),
                roster.rosterId(),
                toOwner(roster.ownerId(), owner),
                toRecord(roster.settings()),
                toStarters(orEmpty(league.rosterPositions()), starterIds, players),
                benchIds.stream().map(players::get).toList(),
                reserveIds.stream().map(players::get).toList(),
                taxiIds.stream().map(players::get).toList());
    }

    /** Pairs each non-bench slot with the starter in the same position of the starters list. */
    private static List<RosterResponse.Starter> toStarters(List<String> rosterPositions, List<String> starterIds,
            Map<String, PlayerSummary> players) {
        List<String> slots = rosterPositions.stream().filter(slot -> !BENCH_SLOT.equals(slot)).toList();
        List<RosterResponse.Starter> starters = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            String playerId = i < starterIds.size() ? starterIds.get(i) : null;
            starters.add(new RosterResponse.Starter(slots.get(i), playerId != null ? players.get(playerId) : null));
        }
        return starters;
    }

    /** Sleeper has no bench list; it is every rostered player not starting, on IR or on the taxi squad. */
    private static List<String> bench(List<String> players, List<String> starters, List<String> reserve, List<String> taxi) {
        Set<String> notOnBench = new HashSet<>(starters);
        notOnBench.addAll(reserve);
        notOnBench.addAll(taxi);
        return players.stream().filter(id -> !notOnBench.contains(id)).toList();
    }

    private static RosterResponse.Owner toOwner(String ownerId, SleeperLeagueUser owner) {
        if (owner == null) {
            return new RosterResponse.Owner(ownerId, null, null, null);
        }
        return new RosterResponse.Owner(ownerId, owner.displayName(), owner.teamName(), thumbUrl(owner.avatar()));
    }

    private static RosterResponse.TeamRecord toRecord(SleeperRoster.Settings s) {
        if (s == null) {
            return new RosterResponse.TeamRecord(0, 0, 0, 0, 0);
        }
        return new RosterResponse.TeamRecord(
                s.wins(), s.losses(), s.ties(),
                s.fpts() + s.fptsDecimal() / 100.0,
                s.fptsAgainst() + s.fptsAgainstDecimal() / 100.0);
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list != null ? list : List.of();
    }
}
