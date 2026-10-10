package com.demo.auth.provider.google;

import com.demo.auth.provider.Identity;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GoogleProviderTests {

    @Test
    void onlyAVerifiedEmailBecomesAnIdentity() {
        assertEquals(new Identity("ada@example.com", "Ada"),
                GoogleProvider.identity(Map.of("email", "ada@example.com", "name", "Ada", "email_verified", true)));
        assertEquals(new Identity("ada@example.com", ""),
                GoogleProvider.identity(Map.of("email", "ada@example.com", "email_verified", "true")),
                "the string form counts too, and a missing name is blank rather than a refusal");

        assertThrows(IllegalStateException.class,
                () -> GoogleProvider.identity(Map.of("email", "ada@example.com", "email_verified", false)));
        assertThrows(IllegalStateException.class,
                () -> GoogleProvider.identity(Map.of("email", "ada@example.com")),
                "no flag at all is unverified: the address would otherwise open somebody else's user");
    }
}
