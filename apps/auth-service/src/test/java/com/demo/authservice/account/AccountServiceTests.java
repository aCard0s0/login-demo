package com.demo.authservice.account;

import com.demo.authservice.token.Tokens;
import org.junit.jupiter.api.Test;
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
        "spring.datasource.url=jdbc:sqlite:target/test-account.db",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        // SQLite allows a single writer; one connection keeps Hibernate from tripping over itself.
        "spring.datasource.hikari.maximum-pool-size=1",
})
class AccountServiceTests {

    @Autowired
    AccountService auth;

    @Autowired
    AccountRepository accounts;

    @Autowired
    Tokens tokens;

    @Test
    void registerHashesThePasswordAndMakesAPlainUser() {
        Account created = auth.register("Ada", "ada@example.com", "correct-horse");

        assertNotEquals("correct-horse", created.getPasswordHash(), "password must not be stored in plain text");
        assertTrue(created.getPasswordHash().startsWith("$2"), "password must be BCrypt hashed");
        assertEquals(Role.USER, created.getRole(), "registration must not be a way to ask for a role");
    }

    @Test
    void onlyAChangeOfRoleMovesAnAccountOffUser() {
        Account admin = auth.ensureAdmin("boss@example.com", "boss-pass-01");
        Account user = auth.register("Rosalind", "rosalind@example.com", "franklin-1920");

        assertEquals(Role.MODERATOR, auth.changeRole(admin, user.getId(), Role.MODERATOR).getRole());
        assertEquals(Role.USER, auth.changeRole(admin, user.getId(), Role.USER).getRole(), "and back again");

        assertThrows(IllegalArgumentException.class, () -> auth.changeRole(admin, user.getId(), null));
        assertThrows(IllegalArgumentException.class, () -> auth.changeRole(admin, 999_999L, Role.MODERATOR));
    }

    @Test
    void anAdminCannotDemoteItself() {
        Account admin = auth.ensureAdmin("last@example.com", "last-pass-01");

        assertThrows(IllegalArgumentException.class, () -> auth.changeRole(admin, admin.getId(), Role.USER),
                "the last admin demoting itself would leave nobody able to promote anyone");
        assertEquals(Role.ADMIN, auth.changeRole(admin, admin.getId(), Role.ADMIN).getRole(),
                "setting the role it already has is a no-op, not an error");
    }

    @Test
    void seedingTheAdminIsCreateOnlyAndPromotesAnAccountThatIsAlreadyThere() {
        Account first = auth.ensureAdmin("seed@example.com", "seed-pass-01");
        assertEquals(Role.ADMIN, first.getRole());

        // A restart with a changed .env must not reset a password the admin has since changed.
        Account again = auth.ensureAdmin("seed@example.com", "a-completely-different-password");
        assertEquals(first.getId(), again.getId(), "seeding twice must not make a second account");
        assertEquals(first.getPasswordHash(), again.getPasswordHash(), "seeding must never reset the password");

        Account registered = auth.register("Later", "later@example.com", "later-pass-01");
        assertEquals(Role.ADMIN, auth.ensureAdmin("later@example.com", "ignored-entirely").getRole(),
                "naming an existing account as the admin promotes it");
        assertEquals(registered.getId(), accounts.findByEmail("later@example.com").orElseThrow().getId());
    }

    @Test
    void rejectsBadInputAndDuplicateEmail() {
        auth.register("Alan", "alan@example.com", "enigma-1936");

        assertThrows(IllegalArgumentException.class, () -> auth.register("", "x@example.com", "long-enough"));
        assertThrows(IllegalArgumentException.class, () -> auth.register("X", "not-an-email", "long-enough"));
        assertThrows(IllegalArgumentException.class, () -> auth.register("X", "x@example.com", "short"));
        assertThrows(IllegalArgumentException.class, () -> auth.register("X", "y@example.com", "x".repeat(73)),
                "BCrypt reads 72 bytes, so a longer password would be accepted truncated");
        assertThrows(IllegalArgumentException.class, () -> auth.register("Alan again", "alan@example.com", "long-enough"));
    }

    @Test
    void updateNeedsTheCurrentPasswordAndKeepsEmailsUnique() {
        Account taken = auth.register("Taken", "taken@example.com", "taken-pass-1");
        Account edna = auth.register("Edna", "edna@example.com", "edna-pass-01");

        assertThrows(IllegalArgumentException.class,
                () -> auth.update(edna.getId(), "Edna", "edna@example.com", "wrong", null),
                "the current password must be checked even when only the name changes");
        assertThrows(IllegalArgumentException.class,
                () -> auth.update(edna.getId(), "Edna", taken.getEmail(), "edna-pass-01", null),
                "an update must not be able to steal another account's email");

        Account renamed = auth.update(edna.getId(), "Edna Mode", "edna.mode@example.com", "edna-pass-01", "new-pass-007");
        assertEquals("Edna Mode", renamed.getName());
        assertEquals("edna.mode@example.com", renamed.getEmail());
    }

    @Test
    void byTokenResolvesASignedTokenAndRejectsATamperedOne() {
        Account grace = auth.register("Grace", "grace@example.com", "hopper-1906");
        String token = tokens.issue(grace.getId(), grace.getEmail(), grace.getName(), grace.getRole().name());

        assertEquals("grace@example.com", auth.byToken(token).orElseThrow().getEmail());
        assertTrue(auth.byToken(token.substring(0, token.length() - 2)).isEmpty(), "a clipped signature must not verify");
        assertTrue(auth.byToken("not.a.token").isEmpty());
        assertTrue(auth.byToken(null).isEmpty());
    }

    @Test
    void tokensStopVerifyingOnceTheyHaveExpired() throws Exception {
        // A negative lifetime makes every token already expired, so the test does not have to wait.
        Tokens expired = new Tokens(Duration.ofSeconds(-1));
        AccountService stale = new AccountService(accounts, expired);
        Account edsger = stale.register("Edsger", "edsger@example.com", "dijkstra-1930");
        // The same Tokens that signed it does the checking, so only the expiry can be what rejects it.
        String token = expired.issue(edsger.getId(), edsger.getEmail(), edsger.getName(), edsger.getRole().name());

        assertTrue(stale.byToken(token).isEmpty(), "a token past its expiry must not verify");
    }

    @Test
    void oneServiceCannotVerifyAnotherServicesTokens() throws Exception {
        Account barbara = auth.register("Barbara", "barbara@example.com", "liskov-1939");
        String token = tokens.issue(barbara.getId(), barbara.getEmail(), barbara.getName(), barbara.getRole().name());

        AccountService other = new AccountService(accounts, new Tokens(Duration.ofMinutes(30)));
        assertTrue(other.byToken(token).isEmpty(), "a different keypair must not accept this token");
    }
}
