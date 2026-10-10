package com.demo.accountservice.account.dto;

/** Transfer request body. The source is the account in the path; {@code amount} is in minor units. */
public record NewTransfer(Long to, Long amount) {}
