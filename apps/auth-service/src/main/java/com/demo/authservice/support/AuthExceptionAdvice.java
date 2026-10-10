package com.demo.authservice.support;

import com.demo.web.errors.ErrorBodyAdvice;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** auth-service's own rejections; everything MVC itself refuses is shaped by {@link ErrorBodyAdvice}. */
@RestControllerAdvice
public class AuthExceptionAdvice extends ErrorBodyAdvice {

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

    /** A locked-out email is not a bad request, it is a rate limit, so it gets its own status. */
    @ExceptionHandler(TooManyAttemptsException.class)
    public ResponseEntity<?> tooManyAttempts(TooManyAttemptsException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", e.getMessage()));
    }
}
