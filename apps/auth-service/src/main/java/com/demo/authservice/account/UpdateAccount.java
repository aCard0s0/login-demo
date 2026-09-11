package com.demo.authservice.account;

/** Account edit request body. currentPassword is always required; newPassword is optional. */
public record UpdateAccount(String name, String email, String currentPassword, String newPassword) {}
