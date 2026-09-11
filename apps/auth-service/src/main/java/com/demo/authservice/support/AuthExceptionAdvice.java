package com.demo.authservice.support;

import com.demo.authservice.session.TooManyAttemptsException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** Turns the domain's rejections into the {error} shape the frontend already renders. */
@RestControllerAdvice
public class AuthExceptionAdvice {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    /**
     * Two registrations for the same email can both pass the service's check and only collide at the unique
     * index. Report that as the same 400 the check would have produced, not as a 500.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<?> duplicate(DataIntegrityViolationException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "that email is already registered"));
    }

    /**
     * Keeps 401s in the same {error} shape as everything else; Spring's own handling would answer with a
     * ProblemDetail the frontend does not know how to read.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> statusException(ResponseStatusException e) {
        String reason = e.getReason() == null ? e.getStatusCode().toString() : e.getReason();
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", reason));
    }

    /** A locked-out email is not a bad request, it is a rate limit, so it gets its own status. */
    @ExceptionHandler(TooManyAttemptsException.class)
    public ResponseEntity<?> tooManyAttempts(TooManyAttemptsException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", e.getMessage()));
    }
}
