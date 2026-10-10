package com.demo.accountservice.account.dto;

/** Grant request body: READ or WRITE. */
public record SetPermission(Access access) {}
