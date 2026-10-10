package com.demo.walletservice.stats;

/** Numbers anyone may see. Totals say how busy the demo is without naming anybody or any balance. */
public record PublicStats(long wallets, long transfers) {}
