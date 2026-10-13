package com.fantasy.league;

import static com.fantasy.sleeper.SleeperAvatars.thumbUrl;

import java.util.List;

import com.fantasy.sleeper.SleeperLeague;
import com.fantasy.sleeper.SleeperUser;

public record UserLeaguesResponse(User user, String season, List<League> leagues) {

    public record User(String userId, String username, String displayName, String avatarUrl) {
    }

    /** {@code sport} is Sleeper's code: "nfl" or "nba". */
    public record League(String leagueId, String name, String sport, String status, int totalRosters, String avatarUrl) {
    }

    static UserLeaguesResponse from(SleeperUser user, String season, List<SleeperLeague> leagues) {
        return new UserLeaguesResponse(
                new User(user.userId(), user.username(), user.displayName(), thumbUrl(user.avatar())),
                season,
                leagues.stream()
                        .map(l -> new League(l.leagueId(), l.name(), l.sportType().code(), l.status(), l.totalRosters(),
                                thumbUrl(l.avatar())))
                        .toList());
    }
}
