package com.demo.walletservice.wallet.dto;

/** Grant request body: READ or WRITE. */
public record SetGrant(Access access) {}
