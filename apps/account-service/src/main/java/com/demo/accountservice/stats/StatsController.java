package com.demo.accountservice.stats;

import com.demo.accountservice.account.AccountService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Deliberately unauthenticated, for the landing page and the compose healthcheck. Nothing here is scoped to an account. */
@RestController
public class StatsController {

    private final AccountService accounts;

    public StatsController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping("/api/public/bank/stats")
    public PublicStats stats() {
        return new PublicStats(accounts.count(), accounts.transferCount());
    }
}
