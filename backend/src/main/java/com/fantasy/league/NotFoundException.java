package com.fantasy.league;

/** Something the caller asked for does not exist on Sleeper. Mapped to HTTP 404. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
