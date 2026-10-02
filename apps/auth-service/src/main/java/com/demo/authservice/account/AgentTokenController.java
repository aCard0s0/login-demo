package com.demo.authservice.account;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Mints the long-lived token an external agent uses to connect to agent-service as one of a user's agents.
 * agent-service calls this after checking the caller owns that agent; this side only checks the account is
 * there and not suspended.
 *
 * <p>Outside /api like {@code /internal/token-versions}: the web proxy forwards only /api and /mcp, and no
 * service publishes a port, so only the compose network reaches it.
 */
@RestController
public class AgentTokenController {

    public record AgentTokenRequest(Long accountId, Long agentId) {}

    private final AccountService accounts;

    public AgentTokenController(AccountService accounts) {
        this.accounts = accounts;
    }

    @PostMapping("/internal/agent-tokens")
    public Map<String, String> issue(@RequestBody AgentTokenRequest in) {
        return Map.of("token", accounts.issueAgentToken(in.accountId(), in.agentId()));
    }
}
