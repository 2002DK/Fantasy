package com.fantasy.sleeper;

public final class SleeperAvatars {

    private static final String THUMB_BASE_URL = "https://sleepercdn.com/avatars/thumbs/";

    private SleeperAvatars() {
    }

    public static String thumbUrl(String avatarId) {
        return avatarId != null ? THUMB_BASE_URL + avatarId : null;
    }
}
