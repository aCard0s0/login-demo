package com.demo.auth.provider.apple;

import com.demo.auth.provider.Identity;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AppleProviderTests {

    @Test
    void onlyAVerifiedEmailBecomesAnIdentityAndTheNameIsNeverInTheToken() {
        assertEquals(new Identity("ada@example.com", ""),
                AppleProvider.identity(Map.of("email", "ada@example.com", "email_verified", true)));
        assertEquals(new Identity("abc123@privaterelay.appleid.com", ""),
                AppleProvider.identity(Map.of("email", "abc123@privaterelay.appleid.com", "email_verified", "true", "is_private_email", "true")),
                "the string form counts, and a hidden relay address is still a verified one");

        assertThrows(IllegalStateException.class,
                () -> AppleProvider.identity(Map.of("email", "ada@example.com", "email_verified", "false")));
        assertThrows(IllegalStateException.class,
                () -> AppleProvider.identity(Map.of("email", "ada@example.com")), "no flag at all is unverified");
        assertThrows(IllegalStateException.class,
                () -> AppleProvider.identity(Map.of("email_verified", true)), "and no address is no identity");
    }
}
