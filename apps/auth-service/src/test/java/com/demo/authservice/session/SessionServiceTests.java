package com.demo.authservice.session;

import com.demo.authservice.account.AccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Its own database file, so re-creating the schema cannot disturb another test class.
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/test-session.db",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        // SQLite allows a single writer; one connection keeps Hibernate from tripping over itself.
        "spring.datasource.hikari.maximum-pool-size=1",
})
class SessionServiceTests {

    @Autowired
    SessionService sessions;

    @Autowired
    AccountService accounts;

    @Test
    void logsInWithTheRightPasswordAndNothingElse() {
        accounts.register("Ada", "ada@example.com", "correct-horse");

        assertEquals("ada@example.com",
                sessions.login("ada@example.com", "correct-horse").orElseThrow().account().getEmail());
        assertTrue(sessions.login("ada@example.com", "wrong").isEmpty());
        assertTrue(sessions.login("nobody@example.com", "correct-horse").isEmpty(),
                "an unknown email must fail the same way a wrong password does");
    }

    @Test
    void aNewPasswordWorksAndTheOldOneStopsWorking() {
        var edna = accounts.register("Edna", "edna@example.com", "edna-pass-01");
        accounts.update(edna.getId(), "Edna Mode", "edna.mode@example.com", "edna-pass-01", "new-pass-007");

        assertTrue(sessions.login("edna.mode@example.com", "new-pass-007").isPresent(), "the new password must work");
        assertTrue(sessions.login("edna.mode@example.com", "edna-pass-01").isEmpty(), "the old password must not");
    }

    @Test
    void locksAnEmailOutAfterARunOfFailedLogins() {
        accounts.register("Katherine", "katherine@example.com", "johnson-1918");

        for (int attempt = 0; attempt < 5; attempt++) {
            assertTrue(sessions.login("katherine@example.com", "wrong").isEmpty());
        }

        assertThrows(TooManyAttemptsException.class,
                () -> sessions.login("katherine@example.com", "johnson-1918"),
                "once locked out, even the correct password must not get through");
    }
}
