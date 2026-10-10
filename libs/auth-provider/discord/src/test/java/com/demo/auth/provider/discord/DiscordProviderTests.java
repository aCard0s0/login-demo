package com.demo.auth.provider.discord;

import com.demo.auth.provider.Identity;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DiscordProviderTests {

    @Test
    void onlyAVerifiedEmailBecomesAnIdentityAndTheNameFallsBackToTheUsername() {
        assertEquals(new Identity("ada@example.com", "Ada"),
                DiscordProvider.identity(Map.of("id", "1", "username", "ada_l", "global_name", "Ada", "email", "ada@example.com", "verified", true)));
        Map<String, Object> noGlobalName = new HashMap<>(Map.of("username", "ada_l", "email", "ada@example.com", "verified", true));
        noGlobalName.put("global_name", null);
        assertEquals(new Identity("ada@example.com", "ada_l"), DiscordProvider.identity(noGlobalName),
                "Discord sends global_name as null for an account that never set one");

        assertThrows(IllegalStateException.class,
                () -> DiscordProvider.identity(Map.of("username", "ada_l", "email", "ada@example.com", "verified", false)));
        assertThrows(IllegalStateException.class,
                () -> DiscordProvider.identity(Map.of("username", "ada_l", "verified", true)), "the identify scope alone carries no address");
    }
}
