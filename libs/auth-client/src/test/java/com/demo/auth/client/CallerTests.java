package com.demo.auth.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plain JUnit: the role is a string from a token, so anything unrecognised must land on least privilege. */
class CallerTests {

    @Test
    void onlyTheRolesItKnowsGrantAnything() {
        assertTrue(new Caller("1", "ADMIN").writesEveryone());
        assertTrue(new Caller("1", "ADMIN").readsEveryone());
        assertTrue(new Caller("1", "MODERATOR").readsEveryone());
        assertFalse(new Caller("1", "MODERATOR").writesEveryone());

        for (String role : new String[] {"USER", "AGENT", "admin", "SUPERUSER", "", null}) {
            Caller caller = new Caller("1", role);
            assertFalse(caller.readsEveryone(), "role " + role);
            assertFalse(caller.writesEveryone(), "role " + role);
        }
    }
}
