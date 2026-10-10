package com.demo.auth.provider.linkedin;

import com.demo.auth.provider.Identity;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LinkedInProviderTests {

    @Test
    void onlyAVerifiedEmailBecomesAnIdentity() {
        assertEquals(new Identity("ada@example.com", "Ada Lovelace"),
                LinkedInProvider.identity(Map.of("sub", "abc", "email", "ada@example.com", "name", "Ada Lovelace", "email_verified", true)));
        assertEquals(new Identity("ada@example.com", ""),
                LinkedInProvider.identity(Map.of("email", "ada@example.com", "email_verified", "true")));

        assertThrows(IllegalStateException.class,
                () -> LinkedInProvider.identity(Map.of("email", "ada@example.com", "email_verified", false)));
        assertThrows(IllegalStateException.class,
                () -> LinkedInProvider.identity(Map.of("email", "ada@example.com", "name", "Ada")), "no flag at all is unverified");
    }
}
