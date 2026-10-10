package com.demo.auth.provider.github;

import com.demo.auth.provider.Identity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GitHubProviderTests {

    @Test
    void theAddressIsTheVerifiedPrimaryOneAndTheNameFallsBackToTheHandle() {
        List<?> emails = List.of(
                Map.of("email", "old@example.com", "primary", false, "verified", true),
                Map.of("email", "ada@example.com", "primary", true, "verified", true));

        assertEquals(new Identity("ada@example.com", "Ada L"),
                GitHubProvider.identity(Map.of("login", "ada", "name", "Ada L"), emails));
        assertEquals(new Identity("ada@example.com", "ada"),
                GitHubProvider.identity(Map.of("login", "ada"), emails), "no full name: the handle is always there");

        assertThrows(IllegalStateException.class, () -> GitHubProvider.identity(Map.of("login", "ada"),
                List.of(Map.of("email", "ada@example.com", "primary", true, "verified", false))),
                "a primary address that is not verified is no address at all");
        assertThrows(IllegalStateException.class, () -> GitHubProvider.identity(Map.of("login", "ada"), List.of()));
    }
}
