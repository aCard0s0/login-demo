package com.demo.authservice.session;

import com.demo.authservice.user.entities.User;

/** A live login: the bearer token plus the user it belongs to. */
public record Session(String token, User user) {}
