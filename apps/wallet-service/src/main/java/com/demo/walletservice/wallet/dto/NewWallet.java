package com.demo.walletservice.wallet.dto;

/** Open-wallet request body. {@code agentId} set opens it for one of the caller's agents; null opens it for the caller. */
public record NewWallet(String name, Long agentId) {}
