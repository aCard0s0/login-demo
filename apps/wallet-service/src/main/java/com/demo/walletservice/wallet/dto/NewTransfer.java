package com.demo.walletservice.wallet.dto;

/** Transfer request body. The source is the wallet in the path; {@code amount} is in minor units. */
public record NewTransfer(Long to, Long amount) {}
