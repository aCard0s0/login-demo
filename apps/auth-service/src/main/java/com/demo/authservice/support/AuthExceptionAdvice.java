package com.demo.authservice.support;

import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import tools.jackson.databind.exc.InvalidFormatException;

import java.util.Arrays;
import java.util.Map;

/**
 * Turns every rejection into the {error} shape the frontend already renders.
 *
 * <p>Extends Spring's own handler rather than listing exceptions one by one: that base class already knows
 * every way MVC itself says no -- a body that will not parse, an unknown enum value, a non-numeric path id,
 * a wrong method, an unknown path, a {@code ResponseStatusException} -- and would answer each with a
 * ProblemDetail the frontend does not read. Overriding the one method that builds the response is what
 * keeps all of them in the same shape as the domain's own rejections below.
 */
@RestControllerAdvice
public class AuthExceptionAdvice extends ResponseEntityExceptionHandler {

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

    /**
     * A body that will not bind. The one case worth a real sentence is a value outside an enum -- an unknown
     * role -- because the admin page shows this text and "Failed to read request" does not say what to fix.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException e, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        String reason = e.getMostSpecificCause() instanceof InvalidFormatException bad && bad.getTargetType().isEnum()
                ? "unknown value '" + bad.getValue() + "', expected one of " + Arrays.toString(bad.getTargetType().getEnumConstants())
                : "request body could not be read";
        return ResponseEntity.status(status).headers(headers).body(Map.of("error", reason));
    }

    /** Where the base class hands over its ProblemDetail; the detail is the human-readable half of it. */
    @Override
    protected ResponseEntity<Object> createResponseEntity(@Nullable Object body, HttpHeaders headers,
                                                          HttpStatusCode statusCode, WebRequest request) {
        String reason = body instanceof ProblemDetail problem && problem.getDetail() != null
                ? problem.getDetail()
                : statusCode.toString();
        return ResponseEntity.status(statusCode).headers(headers).body(Map.of("error", reason));
    }
}
