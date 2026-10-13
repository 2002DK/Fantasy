package com.fantasy.league;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.fantasy.sleeper.SleeperClient;
import com.fantasy.sleeper.SleeperLeague;
import com.fantasy.sleeper.SleeperUser;
import com.fantasy.sleeper.Sport;

@Service
public class LeagueService {

    private final SleeperClient sleeperClient;

    public LeagueService(SleeperClient sleeperClient) {
        this.sleeperClient = sleeperClient;
    }

    /**
     * Looks up a Sleeper user's football and basketball leagues for a season. When
     * {@code season} is null, each sport uses the season Sleeper reports as current for it
     * (both name a season by the year it starts), and the response reports football's.
     */
    public UserLeaguesResponse findLeagues(String username, String season) {
        SleeperUser user = sleeperClient.getUser(username)
                .orElseThrow(() -> new UserNotFoundException(username));
        List<SleeperLeague> leagues = new ArrayList<>();
        String reportedSeason = null;
        for (Sport sport : Sport.values()) {
            String sportSeason = season != null ? season : sleeperClient.getState(sport).season();
            if (reportedSeason == null) {
                reportedSeason = sportSeason;
            }
            leagues.addAll(sleeperClient.getLeagues(user.userId(), sport, sportSeason));
        }
        return UserLeaguesResponse.from(user, reportedSeason, leagues);
    }
}
