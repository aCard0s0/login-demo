package com.demo.agentservice.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthHeaderCryptoTests {

    @Test
    void roundTripsWithAFreshNonceAndRefusesTheWrongKeyOrATamperedRow() {
        AuthHeaderCrypto crypto = new AuthHeaderCrypto("one key");
        String stored = crypto.convertToDatabaseColumn("Bearer s3cret");

        assertTrue(stored.startsWith("v1:"));
        assertNotEquals(stored, crypto.convertToDatabaseColumn("Bearer s3cret"), "a fresh nonce every time: equal headers never look alike");
        assertEquals("Bearer s3cret", crypto.convertToEntityAttribute(stored));
        assertEquals("plain from before", crypto.convertToEntityAttribute("plain from before"), "a legacy value is read as it is");
        assertNull(crypto.convertToDatabaseColumn(null));
        assertNull(crypto.convertToEntityAttribute(null));

        assertThrows(IllegalStateException.class, () -> new AuthHeaderCrypto("other key").convertToEntityAttribute(stored));
        assertThrows(IllegalStateException.class, () -> crypto.convertToEntityAttribute(stored.substring(0, stored.length() - 4) + "AAAA"));
        assertThrows(IllegalStateException.class, () -> new AuthHeaderCrypto(" "), "no key, no start: the headers would be plain text");
    }
}
