package com.demo.authservice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Inline rather than a test application.properties, which would shadow the main one instead of merging over it.
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/test.db",
        "spring.jpa.hibernate.ddl-auto=create-drop",
})
class AuthServiceTests {

    @Autowired
    AuthService auth;

    @Test
    void registersHashesAndLogsIn() {
        Account created = auth.register("Ada", "ada@example.com", "correct-horse");

        assertNotEquals("correct-horse", created.getPasswordHash(), "password must not be stored in plain text");
        assertTrue(created.getPasswordHash().startsWith("$2"), "password must be BCrypt hashed");

        assertTrue(auth.login("ada@example.com", "correct-horse").isPresent());
        assertTrue(auth.login("ada@example.com", "wrong").isEmpty());
        assertTrue(auth.login("nobody@example.com", "correct-horse").isEmpty());
    }

    @Test
    void verifyResolvesTheTokenAndLogoutKillsIt() {
        auth.register("Grace", "grace@example.com", "hopper-1906");
        String token = auth.login("grace@example.com", "hopper-1906").orElseThrow().token();

        assertEquals("grace@example.com", auth.verify(token).orElseThrow().getEmail());
        auth.logout(token);
        assertTrue(auth.verify(token).isEmpty());
    }

    @Test
    void rejectsBadInputAndDuplicateEmail() {
        auth.register("Alan", "alan@example.com", "enigma-1936");

        assertThrows(IllegalArgumentException.class, () -> auth.register("", "x@example.com", "long-enough"));
        assertThrows(IllegalArgumentException.class, () -> auth.register("X", "not-an-email", "long-enough"));
        assertThrows(IllegalArgumentException.class, () -> auth.register("X", "x@example.com", "short"));
        assertThrows(IllegalArgumentException.class, () -> auth.register("Alan again", "alan@example.com", "long-enough"));
    }
}
