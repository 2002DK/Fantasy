package com.fantasy.league;

/** The request is well-formed but asks for something that cannot be answered. Mapped to HTTP 400. */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
