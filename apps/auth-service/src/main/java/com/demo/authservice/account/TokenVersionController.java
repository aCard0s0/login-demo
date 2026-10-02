package com.demo.authservice.account;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * What todo-service polls to learn which tokens auth-service has revoked, since it checks tokens itself and
 * would otherwise honour a revoked one until it expired.
 *
 * <p>Outside /api on purpose: the web proxy forwards only /api, and neither service publishes a port, so
 * only the services on the compose network can reach this. It names account ids and counters, nothing else.
 */
@RestController
public class TokenVersionController {

    private final AccountService accounts;

    public TokenVersionController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping("/internal/token-versions")
    public Map<Long, Integer> tokenVersions() {
        return accounts.tokenVersions();
    }
}
