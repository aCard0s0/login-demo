package com.demo.authservice.user;

import com.demo.authservice.token.Tokens;
import com.demo.authservice.user.entities.AgentTokenVersionRepository;
import com.demo.authservice.user.entities.Role;
import com.demo.authservice.user.entities.User;
import com.demo.authservice.user.entities.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Inline rather than a test application.properties, which would shadow the main one instead of merging over it.
// Its own database file, so re-creating the schema cannot disturb another test class.
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/test-user.db",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        // SQLite allows a single writer; one connection keeps Hibernate from tripping over itself.
        "spring.datasource.hikari.maximum-pool-size=1",
})
class UserServiceTests {

    @Autowired
    UserService auth;

    @Autowired
    UserRepository users;

    @Autowired
    AgentTokenVersionRepository agentVersions;

    @Test
    void registerHashesThePasswordAndMakesAPlainUser() {
        User created = auth.register("Ada", "ada@example.com", "correct-horse");

        assertNotEquals("correct-horse", created.getPasswordHash(), "password must not be stored in plain text");
        assertTrue(created.getPasswordHash().startsWith("$2"), "password must be BCrypt hashed");
        assertEquals(Role.USER, created.getRole(), "registration must not be a way to ask for a role");
    }

    @Test
    void onlyAChangeOfRoleMovesAUserOffTheUserRole() {
        User admin = auth.ensureAdmin("boss@example.com", "boss-pass-01");
        User user = auth.register("Rosalind", "rosalind@example.com", "franklin-1920");

        String before = auth.issue(user);
        assertEquals(Role.MODERATOR, auth.changeRole(admin, user.getId(), Role.MODERATOR).getRole());
        assertTrue(auth.byToken(before).isEmpty(),
                "the role rides in the token and the other services read it from there, so the old one must die");
        User demoted = auth.changeRole(admin, user.getId(), Role.USER);
        assertEquals(Role.USER, demoted.getRole(), "and back again");
        assertEquals(demoted.getTokenVersion(), auth.tokenVersions().get(String.valueOf(user.getId())),
                "and the feed the other services poll says so");
        assertEquals(demoted.getTokenVersion(), auth.changeRole(admin, user.getId(), Role.USER).getTokenVersion(),
                "setting the role it already has revokes nothing");

        assertThrows(ResponseStatusException.class, () -> auth.changeRole(admin, user.getId(), null));
        assertThrows(ResponseStatusException.class, () -> auth.changeRole(admin, 999_999L, Role.MODERATOR));
    }

    @Test
    void anAdminCannotDemoteItself() {
        User admin = auth.ensureAdmin("last@example.com", "last-pass-01");

        assertThrows(ResponseStatusException.class, () -> auth.changeRole(admin, admin.getId(), Role.USER),
                "the last admin demoting itself would leave nobody able to promote anyone");
        assertEquals(Role.ADMIN, auth.changeRole(admin, admin.getId(), Role.ADMIN).getRole(),
                "setting the role it already has is a no-op, not an error");
    }

    @Test
    void seedingTheAdminIsCreateOnlyAndPromotesAUserThatIsAlreadyThere() {
        User first = auth.ensureAdmin("seed@example.com", "seed-pass-01");
        assertEquals(Role.ADMIN, first.getRole());

        // A restart with a changed .env must not reset a password the admin has since changed.
        User again = auth.ensureAdmin("seed@example.com", "a-completely-different-password");
        assertEquals(first.getId(), again.getId(), "seeding twice must not make a second user");
        assertEquals(first.getPasswordHash(), again.getPasswordHash(), "seeding must never reset the password");

        User registered = auth.register("Later", "later@example.com", "later-pass-01");
        assertEquals(Role.ADMIN, auth.ensureAdmin("later@example.com", "ignored-entirely").getRole(),
                "naming an existing user as the admin promotes it");
        assertEquals(registered.getId(), users.findByEmail("later@example.com").orElseThrow().getId());
    }

    @Test
    void rejectsBadInputAndDuplicateEmail() {
        auth.register("Alan", "alan@example.com", "enigma-1936");

        assertThrows(ResponseStatusException.class, () -> auth.register("", "x@example.com", "long-enough"));
        assertThrows(ResponseStatusException.class, () -> auth.register("X", "not-an-email", "long-enough"));
        assertThrows(ResponseStatusException.class, () -> auth.register("X", "x@example.com", "short"));
        assertThrows(ResponseStatusException.class, () -> auth.register("X", "y@example.com", "x".repeat(73)),
                "BCrypt reads 72 bytes, so a longer password would be accepted truncated");
        assertThrows(ResponseStatusException.class, () -> auth.register("Alan again", "alan@example.com", "long-enough"));
        assertThrows(ResponseStatusException.class, () -> auth.register("N".repeat(256), "n@example.com", "long-enough"),
                "the column is varchar(255), so a longer name must be refused here and not by the database");
        assertThrows(ResponseStatusException.class,
                () -> auth.register("Long", "l".repeat(250) + "@example.com", "long-enough"));
    }

    @Test
    void updateNeedsTheCurrentPasswordAndKeepsEmailsUnique() {
        User taken = auth.register("Taken", "taken@example.com", "taken-pass-1");
        User edna = auth.register("Edna", "edna@example.com", "edna-pass-01");

        assertThrows(ResponseStatusException.class,
                () -> auth.update(edna.getId(), "Edna", "edna@example.com", "wrong", null),
                "the current password must be checked even when only the name changes");
        assertThrows(ResponseStatusException.class,
                () -> auth.update(edna.getId(), "Edna", taken.getEmail(), "edna-pass-01", null),
                "an update must not be able to steal another user's email");

        User renamed = auth.update(edna.getId(), "Edna Mode", "edna.mode@example.com", "edna-pass-01", "new-pass-007");
        assertEquals("Edna Mode", renamed.getName());
        assertEquals("edna.mode@example.com", renamed.getEmail());
    }

    @Test
    void byTokenResolvesASignedTokenAndRejectsATamperedOne() {
        User grace = auth.register("Grace", "grace@example.com", "hopper-1906");
        String token = auth.issue(grace);

        assertEquals("grace@example.com", auth.byToken(token).orElseThrow().getEmail());
        assertTrue(auth.byToken(token.substring(0, token.length() - 2)).isEmpty(), "a clipped signature must not verify");
        assertTrue(auth.byToken("not.a.token").isEmpty());
        assertTrue(auth.byToken(null).isEmpty());
    }

    @Test
    void tokensStopVerifyingOnceTheyHaveExpired() throws Exception {
        // A negative lifetime makes every token already expired, so the test does not have to wait.
        Tokens expired = new Tokens(Duration.ofSeconds(-1));
        UserService stale = new UserService(users, agentVersions, expired);
        User edsger = stale.register("Edsger", "edsger@example.com", "dijkstra-1930");
        // The same Tokens that signed it does the checking, so only the expiry can be what rejects it.
        String token = expired.issue(edsger.getId(), edsger.getEmail(), edsger.getName(), edsger.getRole().name(), 0);

        assertTrue(stale.byToken(token).isEmpty(), "a token past its expiry must not verify");
    }

    @Test
    void oneServiceCannotVerifyAnotherServicesTokens() throws Exception {
        User barbara = auth.register("Barbara", "barbara@example.com", "liskov-1939");
        String token = auth.issue(barbara);

        UserService other = new UserService(users, agentVersions, new Tokens(Duration.ofMinutes(30)));
        assertTrue(other.byToken(token).isEmpty(), "a different keypair must not accept this token");
    }

    @Test
    void suspendingKillsTheTokensAUserHoldsAndReactivatingDoesNotRevive() {
        User admin = auth.register("Root", "root@example.com", "root-pass-01");
        User alan = auth.register("Alan", "turing@example.com", "turing-1912");
        String before = auth.issue(alan);

        auth.setSuspended(admin, alan.getId(), true);
        assertTrue(auth.byToken(before).isEmpty(), "a suspended user's token must stop working at once");

        User back = auth.setSuspended(admin, alan.getId(), false);
        assertTrue(auth.byToken(before).isEmpty(), "reactivating must not bring an old token back");
        assertEquals(alan.getId(), auth.byToken(auth.issue(back)).orElseThrow().getId(), "a new token works");

        assertThrows(ResponseStatusException.class, () -> auth.setSuspended(admin, admin.getId(), true),
                "an admin must not be able to lock itself out");
    }

    @Test
    void revokingKillsEveryLiveTokenButNotTheNextOne() {
        User linus = auth.register("Linus", "linus@example.com", "torvalds-1969");
        String first = auth.issue(linus);
        String second = auth.issue(linus);

        User revoked = auth.revokeTokens(linus.getId());
        assertTrue(auth.byToken(first).isEmpty());
        assertTrue(auth.byToken(second).isEmpty());
        assertEquals(revoked.getTokenVersion(), auth.tokenVersions().get(String.valueOf(linus.getId())),
                "todo-service learns the new version from this map");
        assertTrue(auth.byToken(auth.issue(revoked)).isPresent(), "logging back in still works");
    }
}
