package com.demo.authservice.user.dto;

/** User edit request body. currentPassword is always required; newPassword is optional. */
public record UpdateUser(String name, String email, String currentPassword, String newPassword) {}
