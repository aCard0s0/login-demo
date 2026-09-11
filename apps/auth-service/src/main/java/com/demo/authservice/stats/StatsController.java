package com.demo.authservice.stats;

import com.demo.authservice.account.AccountService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Deliberately unauthenticated, for the landing page. Nothing here names an account. */
@RestController
public class StatsController {

    private final AccountService accounts;

    public StatsController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping("/api/public/stats")
    public PublicStats stats() {
        return new PublicStats(accounts.count());
    }
}
