package com.demo.walletservice.stats;

import com.demo.walletservice.wallet.WalletService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Deliberately unauthenticated, for the landing page and the compose healthcheck. Nothing here is scoped to a wallet. */
@RestController
public class StatsController {

    private final WalletService wallets;

    public StatsController(WalletService wallets) {
        this.wallets = wallets;
    }

    @GetMapping("/api/public/wallets/stats")
    public PublicStats stats() {
        return new PublicStats(wallets.count(), wallets.transferCount());
    }
}
