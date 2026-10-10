package com.demo.authservice.session;

import com.demo.authservice.user.entities.Role;

/** A successful login: the bearer token to send back on every later request, plus who it belongs to. */
public record LoginResponse(String token, String name, String email, Role role) {}
