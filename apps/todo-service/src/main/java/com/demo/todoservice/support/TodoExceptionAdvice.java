package com.demo.todoservice.support;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** The same {error} shape auth-service answers with, so the frontend reads one kind of error body. */
@RestControllerAdvice
public class TodoExceptionAdvice {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> statusException(ResponseStatusException e) {
        String reason = e.getReason() == null ? e.getStatusCode().toString() : e.getReason();
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", reason));
    }
}
