package com.demo.accountservice.account;

/** Grant request body: READ or WRITE. */
public record SetPermission(Access access) {}
