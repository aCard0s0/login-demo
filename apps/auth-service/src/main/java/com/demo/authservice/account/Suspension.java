package com.demo.authservice.account;

/** Suspend or reactivate request body. Admin only. */
public record Suspension(boolean suspended) {}
