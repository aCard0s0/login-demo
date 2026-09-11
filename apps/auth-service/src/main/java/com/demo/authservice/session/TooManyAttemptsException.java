package com.demo.authservice.session;

/** Thrown instead of a plain rejection once an email has failed too often. The web advice turns it into a 429. */
public class TooManyAttemptsException extends RuntimeException {

    public TooManyAttemptsException(String message) {
        super(message);
    }
}
