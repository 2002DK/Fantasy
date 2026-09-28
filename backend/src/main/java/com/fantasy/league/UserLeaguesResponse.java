package com.fantasy.league;

import java.util.List;

import com.fantasy.sleeper.SleeperLeague;
import com.fantasy.sleeper.SleeperUser;

public record UserLeaguesResponse(User user, String season, List<League> leagues) {

    private static final String AVATAR_BASE_URL = "https://sleepercdn.com/avatars/thumbs/";

    public record User(String userId, String username, String displayName, String avatarUrl) {
    }

    public record League(String leagueId, String name, String status, int totalRosters, String avatarUrl) {
    }

    static UserLeaguesResponse from(SleeperUser user, String season, List<SleeperLeague> leagues) {
        return new UserLeaguesResponse(
                new User(user.userId(), user.username(), user.displayName(), avatarUrl(user.avatar())),
                season,
                leagues.stream()
                        .map(l -> new League(l.leagueId(), l.name(), l.status(), l.totalRosters(), avatarUrl(l.avatar())))
                        .toList());
    }

    private static String avatarUrl(String avatarId) {
        return avatarId != null ? AVATAR_BASE_URL + avatarId : null;
    }
}
