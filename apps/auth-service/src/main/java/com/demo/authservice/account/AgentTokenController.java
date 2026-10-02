package com.demo.authservice.account;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Mints the long-lived token an external agent uses to connect to agent-service as one of a user's agents.
 * agent-service calls this after checking the caller owns that agent; this side only checks the account is
 * there and not suspended.
 *
 * <p>Outside /api like {@code /internal/token-versions}, so the web proxy never forwards it -- but unlike that
 * read-only list, this one hands out credentials for any account, so being on the compose network is not
 * enough: the caller must also present the shared {@code X-Internal-Secret} that agent-service is configured
 * with. A blank secret turns the endpoint off rather than open.
 */
@RestController
public class AgentTokenController {

    static final String SECRET_HEADER = "X-Internal-Secret";

    public record AgentTokenRequest(Long accountId, Long agentId) {}

    private final AccountService accounts;

    private final byte[] secret;

    public AgentTokenController(AccountService accounts, @Value("${auth.internal-secret:}") String secret) {
        this.accounts = accounts;
        this.secret = secret.strip().getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/internal/agent-tokens")
    public Map<String, String> issue(@RequestHeader(value = SECRET_HEADER, required = false) String presented,
                                     @RequestBody AgentTokenRequest in) {
        byte[] given = presented == null ? new byte[0] : presented.strip().getBytes(StandardCharsets.UTF_8);
        // Constant-time, and a blank configured secret matches nothing: an unset deployment is closed, not open.
        if (secret.length == 0 || !MessageDigest.isEqual(secret, given)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "internal secret missing or wrong");
        }
        return Map.of("token", accounts.issueAgentToken(in.accountId(), in.agentId()));
    }
}
