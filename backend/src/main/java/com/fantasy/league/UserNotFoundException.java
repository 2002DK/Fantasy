package com.fantasy.league;

public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException(String username) {
        super("No Sleeper user found with username '" + username + "'");
    }
}
