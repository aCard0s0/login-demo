package com.demo.authservice.user.dto;

/** Registration request body. */
public record NewUser(String name, String email, String password) {}
