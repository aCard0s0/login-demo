package com.demo.authservice.account;

/** A registered account as the frontend sees it. No password field, hashed or otherwise. */
public record AccountResponse(Long id, String name, String email) {}
