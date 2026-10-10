package com.demo.authservice.user.dto;

import com.demo.authservice.user.entities.Role;

/** Role-change request body. Admin only, and the only way a user ever leaves USER. */
public record RoleChange(Role role) {}
