package com.fantasy.league;

import static com.fantasy.sleeper.SleeperAvatars.thumbUrl;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.fantasy.player.PlayerService;
import com.fantasy.player.PlayerSummary;
import com.fantasy.sleeper.SleeperClient;
import com.fantasy.sleeper.SleeperLeague;
import com.fantasy.sleeper.SleeperLeagueUser;
import com.fantasy.sleeper.SleeperRoster;

/** Loads a league with every roster, owner and rostered player in three Sleeper calls and one query. */
@Component
public class LeagueData {

    /** One team: its Sleeper roster, owner and players resolved from the player cache. */
    public record Team(SleeperRoster roster, RosterResponse.Owner owner, List<PlayerSummary> players) {

        public int rosterId() {
            return roster.rosterId();
        }

        public String name() {
            if (owner.teamName() != null) {
                return owner.teamName();
            }
            return owner.displayName() != null ? owner.displayName() : "Team " + roster.rosterId();
        }
    }

    public record Snapshot(SleeperLeague league, List<Team> teams) {

        public Team teamOf(String userId) {
            return teams.stream().filter(t -> t.roster().isOwnedBy(userId)).findFirst()
                    .orElseThrow(() -> new NotFoundException(
                            "User '" + userId + "' has no roster in league '" + league.name() + "'"));
        }

        public Team team(int rosterId) {
            return teams.stream().filter(t -> t.rosterId() == rosterId).findFirst().orElse(null);
        }

        /** The team that rosters the player, or null for a free agent. */
        public Team teamWithPlayer(String playerId) {
            return teams.stream()
                    .filter(t -> t.players().stream().anyMatch(p -> p.playerId().equals(playerId)))
                    .findFirst()
                    .orElse(null);
        }

        public List<String> rosterPositions() {
            return league.rosterPositions() != null ? league.rosterPositions() : List.of();
        }
    }

    private final SleeperClient sleeperClient;
    private final PlayerService playerService;

    public LeagueData(SleeperClient sleeperClient, PlayerService playerService) {
        this.sleeperClient = sleeperClient;
        this.playerService = playerService;
    }

    public SleeperLeague league(String leagueId) {
        return sleeperClient.getLeague(leagueId)
                .orElseThrow(() -> new NotFoundException("No Sleeper league found with ID '" + leagueId + "'"));
    }

    public Snapshot load(String leagueId) {
        SleeperLeague league = league(leagueId);
        List<SleeperRoster> rosters = sleeperClient.getRosters(leagueId);
        Map<String, SleeperLeagueUser> users = new HashMap<>();
        sleeperClient.getLeagueUsers(leagueId).forEach(u -> users.put(u.userId(), u));

        Set<String> playerIds = new LinkedHashSet<>();
        rosters.forEach(r -> playerIds.addAll(orEmpty(r.players())));
        Map<String, PlayerSummary> players = playerService.findSummaries(playerIds);

        List<Team> teams = rosters.stream()
                .map(r -> new Team(r, owner(r.ownerId(), r.ownerId() != null ? users.get(r.ownerId()) : null),
                        orEmpty(r.players()).stream().map(players::get).toList()))
                .toList();
        return new Snapshot(league, teams);
    }

    private static RosterResponse.Owner owner(String ownerId, SleeperLeagueUser user) {
        if (user == null) {
            return new RosterResponse.Owner(ownerId, null, null, null);
        }
        return new RosterResponse.Owner(ownerId, user.displayName(), user.teamName(), thumbUrl(user.avatar()));
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list != null ? list : List.of();
    }
}
