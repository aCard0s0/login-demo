package com.demo.accountservice.account.dto;

/** Open-account request body. {@code agentId} set opens it for one of the caller's agents; null opens it for the caller. */
public record NewAccount(String name, Long agentId) {}
