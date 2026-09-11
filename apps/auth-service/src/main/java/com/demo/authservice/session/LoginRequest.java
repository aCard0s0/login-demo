package com.demo.authservice.session;

/** Login request body. */
public record LoginRequest(String email, String password) {}
