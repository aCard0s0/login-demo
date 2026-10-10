package com.demo.auth.provider;

/** A verified email and a display name, which is all a service takes from a provider. The name may be blank. */
public record Identity(String email, String name) {}
