package com.fantasy.league;

import java.util.List;

import org.springframework.stereotype.Service;

import com.fantasy.sleeper.SleeperClient;
import com.fantasy.sleeper.SleeperLeague;
import com.fantasy.sleeper.SleeperUser;

@Service
public class LeagueService {

    private final SleeperClient sleeperClient;

    public LeagueService(SleeperClient sleeperClient) {
        this.sleeperClient = sleeperClient;
    }

    /**
     * Looks up a Sleeper user's NFL leagues. When {@code season} is null, uses the
     * season Sleeper currently reports as active.
     */
    public UserLeaguesResponse findLeagues(String username, String season) {
        SleeperUser user = sleeperClient.getUser(username)
                .orElseThrow(() -> new UserNotFoundException(username));
        String resolvedSeason = season != null ? season : sleeperClient.getNflState().season();
        List<SleeperLeague> leagues = sleeperClient.getLeagues(user.userId(), resolvedSeason);
        return UserLeaguesResponse.from(user, resolvedSeason, leagues);
    }
}
