package com.demo.web.errors;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
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
 * keeps all of them in the same shape as each service's domain rejections.
 *
 * <p>Abstract on purpose: each service's own {@code @RestControllerAdvice} extends it, so there is exactly
 * one advice per service and its domain handlers sit next to these.
 */
public abstract class ErrorBodyAdvice extends ResponseEntityExceptionHandler {

    /**
     * A body that will not bind. The one case worth a real sentence is a value outside an enum -- an unknown
     * role, an access level -- because the pages show this text and "Failed to read request" does not say
     * what to fix.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException e, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        String reason = e.getMostSpecificCause() instanceof InvalidFormatException bad && bad.getTargetType().isEnum()
                ? "unknown value '" + bad.getValue() + "', expected one of " + Arrays.toString(bad.getTargetType().getEnumConstants())
                : "request body could not be read";
        return error(status, headers, reason);
    }

    /** Where the base class hands over its ProblemDetail; the detail is the human-readable half of it. */
    @Override
    protected ResponseEntity<Object> createResponseEntity(@Nullable Object body, HttpHeaders headers,
                                                          HttpStatusCode statusCode, WebRequest request) {
        String reason = body instanceof ProblemDetail problem && problem.getDetail() != null
                ? problem.getDetail()
                : statusCode.toString();
        return error(statusCode, headers, reason);
    }

    protected static ResponseEntity<Object> error(HttpStatusCode status, HttpHeaders headers, String reason) {
        return ResponseEntity.status(status).headers(headers).body(Map.of("error", reason));
    }
}
