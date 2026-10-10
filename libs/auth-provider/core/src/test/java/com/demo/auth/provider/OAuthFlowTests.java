package com.demo.auth.provider;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The half that needs no provider: PKCE against the RFC's own vector, and which providers count as offered. */
class OAuthFlowTests {

    static final class Fake extends OAuthProvider {
        Fake(String key) {
            super(key, key, "https://" + key + "/auth", "https://" + key + "/token", "email");
        }

        @Override
        public Identity identity(RestClient http, String accessToken) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void challengeIsS256OfTheVerifierAsTheRfcSpells() {
        // RFC 7636 appendix B.
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                OAuthFlow.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
    }

    @Test
    void onlyAProviderWithBothCredentialsIsOfferedAndTheConsentUriNamesTheCallback() {
        Fake on = new Fake("on");
        on.setEnabled(true);
        on.setClientId("id");
        on.setClientSecret("secret");
        Fake half = new Fake("half");
        half.setEnabled(true);
        half.setClientId("id");

        OAuthProperties config = new OAuthProperties();
        config.setRedirectBaseUrl("http://localhost:3000/");
        OAuthFlow flow = new OAuthFlow(config, List.of(on, half));

        assertEquals(List.of(on), flow.enabled());
        assertTrue(flow.enabled("ON").isPresent(), "the key is a path segment, so case must not matter");
        assertTrue(flow.enabled("half").isEmpty(), "enabled but unconfigured counts as off");

        String consent = flow.consentUri(on, "s", "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk");
        assertTrue(consent.startsWith("https://on/auth?response_type=code&client_id=id&"), consent);
        assertTrue(consent.contains("redirect_uri=http%3A%2F%2Flocalhost%3A3000%2Fapi%2Foauth%2Fon%2Fcallback"), consent);
        assertTrue(consent.endsWith("&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM&code_challenge_method=S256"), consent);
    }
}
