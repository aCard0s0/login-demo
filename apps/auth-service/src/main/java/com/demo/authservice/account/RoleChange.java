package com.demo.authservice.account;

/** Role-change request body. Admin only, and the only way an account ever leaves USER. */
public record RoleChange(Role role) {}
