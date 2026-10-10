package com.demo.agentservice.agent;

import com.demo.jpa.crypto.EncryptedText;
import jakarta.persistence.Converter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encrypts a server row's {@code authHeader} at rest: the {@link EncryptedText} converter of {@code libs/jpa-crypto},
 * keyed by {@code agents.auth-header-key}, which compose refuses to start without, like the internal secret. Blank
 * refuses to start here too, rather than quietly storing plain text. A Spring bean as well as a converter, so
 * Hibernate builds it through Spring and the key is injected.
 */
@Component
@Converter
public class AuthHeaderCrypto extends EncryptedText {

    public AuthHeaderCrypto(@Value("${agents.auth-header-key:}") String key) {
        super(key, "agents.auth-header-key");
    }
}
