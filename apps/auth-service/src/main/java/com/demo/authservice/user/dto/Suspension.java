package com.demo.authservice.user.dto;

/** Suspend or reactivate request body. Admin only. */
public record Suspension(boolean suspended) {}
