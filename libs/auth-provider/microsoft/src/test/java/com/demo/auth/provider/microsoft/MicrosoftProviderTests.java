package com.demo.auth.provider.microsoft;

import com.demo.auth.provider.Identity;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MicrosoftProviderTests {

    @Test
    void onlyAnAddressMicrosoftVouchesForThroughXmsEdovBecomesAnIdentity() {
        assertEquals(new Identity("ada@example.com", "Ada"),
                MicrosoftProvider.identity(Map.of("email", "ada@example.com", "name", "Ada", "xms_edov", true)));
        assertEquals(new Identity("ada@example.com", ""),
                MicrosoftProvider.identity(Map.of("email", "ada@example.com", "xms_edov", "1")),
                "the claim has been seen as the string 1, and a missing name is blank rather than a refusal");

        assertThrows(IllegalStateException.class,
                () -> MicrosoftProvider.identity(Map.of("email", "ada@example.com", "xms_edov", false)));
        assertThrows(IllegalStateException.class,
                () -> MicrosoftProvider.identity(Map.of("email", "ada@example.com", "name", "Ada")),
                "no xms_edov at all is unverified: a tenant admin can put any address in the email claim (nOAuth)");
        assertThrows(IllegalStateException.class,
                () -> MicrosoftProvider.identity(Map.of("preferred_username", "ada@example.com", "xms_edov", true)),
                "the UPN is not an email address, verified or otherwise");
    }
}
