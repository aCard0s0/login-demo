package com.demo.authservice.account;

/** Registration request body. */
public record NewAccount(String name, String email, String password) {}
