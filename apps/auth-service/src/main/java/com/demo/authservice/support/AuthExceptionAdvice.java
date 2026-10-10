package com.demo.authservice.support;

import com.demo.web.errors.ErrorBodyAdvice;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * auth-service's own rejections; everything else -- a {@code ResponseStatusException} from a service, and what
 * MVC itself refuses -- is shaped by {@link ErrorBodyAdvice}. Nothing here catches a broad exception type: a
 * library's {@code IllegalArgumentException} would otherwise reach the client as a 400 carrying its message.
 */
@RestControllerAdvice
public class AuthExceptionAdvice extends ErrorBodyAdvice {

    /**
     * Two registrations for the same email can both pass the service's check and only collide at the unique
     * index. Report that as the same 400 the check would have produced, not as a 500.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<?> duplicate(DataIntegrityViolationException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "that email is already registered"));
    }
}
