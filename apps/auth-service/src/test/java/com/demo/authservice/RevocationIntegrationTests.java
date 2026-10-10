package com.demo.authservice;

import com.demo.auth.client.Caller;
import com.demo.auth.client.JwtVerifier;
import com.demo.auth.client.Revocations;
import com.demo.authservice.session.SessionService;
import com.demo.authservice.user.UserService;
import com.demo.authservice.user.entities.Role;
import com.demo.authservice.user.entities.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Revocation as the other services see it: auth-service on a real port, and libs/auth-client's verifier reading
 * its JWKS and its token-version feed over HTTP, exactly as todo-, agent- and wallet-service do. The role rides
 * in the token, so what matters is not that auth-service forgets an old token but that they do.
 *
 * <p>Each check builds a fresh verifier: the feed is cached for ten seconds, and a fresh one stands for a
 * service whose cache has since turned over.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:sqlite:target/test-revocation.db",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        // SQLite allows a single writer; one connection keeps Hibernate from tripping over itself.
        "spring.datasource.hikari.maximum-pool-size=1",
})
class RevocationIntegrationTests {

    @LocalServerPort
    int port;

    @Autowired
    UserService users;

    @Autowired
    SessionService sessions;

    @Test
    void aDemotedModeratorIsDemotedEverywhereNotOnlyHere() throws Exception {
        User admin = users.ensureAdmin("chief@example.com", "chief-pass-01");
        User mary = users.register("Mary", "mary.j@example.com", "jackson-1921");
        users.changeRole(admin, mary.getId(), Role.MODERATOR);
        String moderator = login("mary.j@example.com", "jackson-1921");
        assertTrue(verifier().callerOf("Bearer " + moderator).readsEveryone(), "the token carries the role it was minted with");

        users.changeRole(admin, mary.getId(), Role.USER);

        assertThrows(ResponseStatusException.class, () -> verifier().callerOf("Bearer " + moderator),
                "the moderator token must die at the other services, which never read the role from the row");
        Caller after = verifier().callerOf("Bearer " + login("mary.j@example.com", "jackson-1921"));
        assertEquals(String.valueOf(mary.getId()), after.userId());
        assertEquals("USER", after.role());
        assertFalse(after.readsEveryone(), "and the next login carries the new role");
    }

    @Test
    void aPasswordChangeAndAnAgentRevokeReachTheOtherServicesToo() throws Exception {
        User linus = users.register("Linus", "linus.t@example.com", "torvalds-1969");
        String before = login("linus.t@example.com", "torvalds-1969");
        String agent = users.issueAgentToken(linus.getId(), 5L);
        assertEquals(5L, verifier().callerOf("Bearer " + agent).agentId());

        users.revokeAgentTokens(5L);
        assertThrows(ResponseStatusException.class, () -> verifier().callerOf("Bearer " + agent));
        assertEquals("USER", verifier().callerOf("Bearer " + before).role(), "revoking one agent leaves its owner alone");

        users.update(linus.getId(), "Linus", "linus.t@example.com", "torvalds-1969", "git-2005-pass");
        assertThrows(ResponseStatusException.class, () -> verifier().callerOf("Bearer " + before),
                "whoever had the old password must not keep its session anywhere");
        verifier().callerOf("Bearer " + login("linus.t@example.com", "git-2005-pass"));
    }

    private String login(String email, String password) {
        return sessions.login(email, password).orElseThrow().token();
    }

    private JwtVerifier verifier() throws Exception {
        String base = "http://localhost:" + port;
        return new JwtVerifier(base + "/api/jwks.json", new Revocations(base + "/internal/token-versions"));
    }
}
