package com.demo.auth.provider.apple;

import com.demo.auth.provider.Identity;
import com.demo.auth.provider.OAuthFlow;
import com.demo.auth.provider.OAuthProvider;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The module as a service gets it: scanned, bound from {@code oauth.apple.*}, minting the client secret Apple
 * demands, asking for a form POST back, and reading the ID token.
 */
class AppleProviderIntegrationTests {

    @Configuration
    @ComponentScan("com.demo.auth.provider")
    @EnableConfigurationProperties
    static class Service {}

    private static final ECKey KEY = generate();

    private static ECKey generate() {
        try {
            return new ECKeyGenerator(Curve.P_256).keyID("KEY1234567").generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The .p8 file as Apple ships it. */
    private static String pem() throws Exception {
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(KEY.toECPrivateKey().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
    }

    @Test
    void allFourHalvesBindAndAKeyThatDoesNotParseCountsAsOff() throws Exception {
        String[] full = {"oauth.apple.enabled=true", "oauth.apple.client-id=com.example.app", "oauth.apple.team-id=TEAM123456",
                "oauth.apple.key-id=KEY1234567", "oauth.apple.private-key=" + pem().replace("\n", "\\n"),
                "oauth.redirect-base-url=https://app.example.com"};
        new ApplicationContextRunner().withUserConfiguration(Service.class).withPropertyValues(full)
                .run(app -> {
                    OAuthFlow flow = app.getBean(OAuthFlow.class);
                    assertEquals(List.of("apple"), flow.enabled().stream().map(OAuthProvider::getKey).toList(),
                            "the .p8 arrives from .env with literal \\n for its line breaks and must still parse");
                    String consent = flow.consentUri(flow.enabled("apple").orElseThrow(), "s", "v".repeat(43));
                    assertTrue(consent.startsWith("https://appleid.apple.com/auth/authorize?"), consent);
                    assertTrue(consent.contains("scope=name+email"), consent);
                    assertTrue(consent.endsWith("&response_mode=form_post"), "Apple insists on it once the email is asked for: " + consent);
                });
        new ApplicationContextRunner().withUserConfiguration(Service.class)
                .withPropertyValues("oauth.apple.enabled=true", "oauth.apple.client-id=com.example.app", "oauth.apple.team-id=TEAM123456",
                        "oauth.apple.key-id=KEY1234567", "oauth.apple.private-key=not a key")
                .run(app -> assertTrue(app.getBean(OAuthFlow.class).enabled().isEmpty(), "a mangled key is off, not a button to an error page"));
        new ApplicationContextRunner().withUserConfiguration(Service.class)
                .withPropertyValues("oauth.apple.enabled=true", "oauth.apple.client-id=com.example.app", "oauth.apple.client-secret=whatever")
                .run(app -> assertTrue(app.getBean(OAuthFlow.class).enabled().isEmpty(), "a client-secret is not what Apple takes"));
    }

    @Test
    void theClientSecretIsAShortLivedJwtSignedWithTheP8KeyAndNamingTheTeamKeyAndServicesId() throws Exception {
        AppleProvider apple = configured();

        SignedJWT secret = SignedJWT.parse(apple.getClientSecret());
        assertTrue(secret.verify(new ECDSAVerifier(KEY.toECPublicKey())), "signed with the .p8 key, ES256");
        assertEquals(JWSAlgorithm.ES256, secret.getHeader().getAlgorithm());
        assertEquals("KEY1234567", secret.getHeader().getKeyID());
        JWTClaimsSet claims = secret.getJWTClaimsSet();
        assertEquals("TEAM123456", claims.getIssuer());
        assertEquals("com.example.app", claims.getSubject());
        assertEquals(List.of(AppleProvider.AUDIENCE), claims.getAudience());
        long life = (claims.getExpirationTime().getTime() - claims.getIssueTime().getTime()) / 1000;
        assertTrue(life > 0 && life <= 6 * 30 * 24 * 3600, "Apple refuses a secret that lives longer than six months: " + life);
        assertNotEquals(apple.getClientSecret(), apple.getClientSecret(), "minted fresh per request, never cached");
    }

    @Test
    void theIdentityIsReadOffTheIdTokenAndNoApiIsAsked() throws Exception {
        AppleProvider apple = configured();
        RSAKey appleKey = new RSAKeyGenerator(2048).generate();
        RestClient never = RestClient.builder().requestFactory((uri, method) -> {
            throw new AssertionError("Apple has no userinfo endpoint to ask");
        }).build();

        assertEquals(new Identity("ada@example.com", ""),
                apple.identity(never, Map.of("access_token", "at", "id_token", idToken(appleKey, "com.example.app", "true"))));
        assertThrows(IllegalStateException.class,
                () -> apple.identity(never, Map.of("access_token", "at", "id_token", idToken(appleKey, "com.example.app", "false"))));
        assertThrows(IllegalStateException.class,
                () -> apple.identity(never, Map.of("access_token", "at", "id_token", idToken(appleKey, "com.other.app", "true"))),
                "a token minted for another Services ID must not open a user here");
    }

    private static AppleProvider configured() throws Exception {
        AppleProvider apple = new AppleProvider();
        apple.setEnabled(true);
        apple.setClientId("com.example.app");
        apple.setTeamId("TEAM123456");
        apple.setKeyId("KEY1234567");
        apple.setPrivateKey(pem());
        assertTrue(apple.usable());
        return apple;
    }

    /** Apple spells email_verified as the string "true" in its ID token. */
    private static String idToken(RSAKey key, String audience, String verified) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), new JWTClaimsSet.Builder()
                .issuer("https://appleid.apple.com").audience(audience).subject("001234.abcdef")
                .expirationTime(new Date(System.currentTimeMillis() + 60_000))
                .claim("email", "ada@example.com").claim("email_verified", verified).build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
