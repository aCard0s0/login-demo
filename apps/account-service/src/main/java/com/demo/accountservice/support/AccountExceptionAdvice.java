package com.demo.accountservice.support;

import com.demo.web.errors.ErrorBodyAdvice;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.util.Map;

/** The same {error} shape auth-service answers with, so the frontend reads one kind of error body. */
@RestControllerAdvice
public class AccountExceptionAdvice extends ErrorBodyAdvice {

    /** A body that does not parse -- a fractional amount, say -- is the caller's mistake, in the same shape. */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException e, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        return error(status, headers, "malformed request body: amounts and ids are whole numbers");
    }

    /** Two requests raced into the same row -- the same grant made twice at once, say. The loser may simply retry. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<?> conflict(DataIntegrityViolationException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "that changed at the same moment elsewhere; try again"));
    }
}
