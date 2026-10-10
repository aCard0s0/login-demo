package com.demo.agentservice.support;

import com.demo.web.errors.ErrorBodyAdvice;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** The same {error} shape auth-service answers with, so the frontend reads one kind of error body. */
@RestControllerAdvice
public class AgentExceptionAdvice extends ErrorBodyAdvice {}
