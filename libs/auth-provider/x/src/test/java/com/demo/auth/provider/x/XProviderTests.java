package com.demo.auth.provider.x;

import com.demo.auth.provider.Identity;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class XProviderTests {

    @Test
    void theAddressIsTheConfirmedOneUnderDataAndTheNameFallsBackToTheHandle() {
        assertEquals(new Identity("ada@example.com", "Ada L"),
                XProvider.identity(Map.of("data", Map.of("id", "1", "username", "ada", "name", "Ada L", "confirmed_email", "ada@example.com"))));
        assertEquals(new Identity("ada@example.com", "ada"),
                XProvider.identity(Map.of("data", Map.of("username", "ada", "confirmed_email", "ada@example.com"))));

        assertThrows(IllegalStateException.class,
                () -> XProvider.identity(Map.of("data", Map.of("id", "1", "username", "ada", "name", "Ada"))),
                "no confirmed_email -- the scope, the app setting or the address itself is missing -- is a refusal");
        assertThrows(IllegalStateException.class, () -> XProvider.identity(Map.of()),
                "X answers {} for an account with no email at all");
        assertThrows(IllegalStateException.class, () -> XProvider.identity(null));
    }
}
