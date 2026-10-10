package com.demo.auth.provider.microsoft;

import com.demo.auth.provider.Identity;
import com.demo.auth.provider.OAuthFlow;
import com.demo.auth.provider.OAuthProvider;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The module as a service gets it: scanned, bound from {@code oauth.microsoft.*}, and reading the ID token. */
class MicrosoftProviderIntegrationTests {

    @Configuration
    @ComponentScan("com.demo.auth.provider")
    @EnableConfigurationProperties
    static class Service {}

    @Test
    void credentialsBindFromItsOwnPrefixAndTheConsentGoesToTheCommonTenant() {
        new ApplicationContextRunner().withUserConfiguration(Service.class)
                .withPropertyValues("oauth.microsoft.enabled=true", "oauth.microsoft.client-id=ms-id", "oauth.microsoft.client-secret=ms-secret",
                        "oauth.redirect-base-url=https://app.example.com")
                .run(app -> {
                    OAuthFlow flow = app.getBean(OAuthFlow.class);
                    assertEquals(List.of("microsoft"), flow.enabled().stream().map(OAuthProvider::getKey).toList());
                    String consent = flow.consentUri(flow.enabled("microsoft").orElseThrow(), "s", "v".repeat(43));
                    assertTrue(consent.startsWith("https://login.microsoftonline.com/common/oauth2/v2.0/authorize?"), consent);
                    assertTrue(consent.contains("scope=openid+email+profile"), consent);
                    assertTrue(consent.contains("redirect_uri=https%3A%2F%2Fapp.example.com%2Fapi%2Foauth%2Fmicrosoft%2Fcallback"), consent);
                    assertFalse(consent.contains("response_mode"), "Microsoft answers with a query string");
                });
        new ApplicationContextRunner().withUserConfiguration(Service.class)
                .withPropertyValues("oauth.microsoft.enabled=true", "oauth.microsoft.client-id=ms-id")
                .run(app -> assertTrue(app.getBean(OAuthFlow.class).enabled().isEmpty(), "a half-finished .env counts as off"));
    }

    @Test
    void theIdentityIsReadOffTheIdTokenMintedForThisClient() throws Exception {
        MicrosoftProvider provider = new MicrosoftProvider();
        provider.setClientId("ms-id");
        RSAKey key = new RSAKeyGenerator(2048).generate();
        RestClient never = RestClient.builder().requestFactory((uri, method) -> {
            throw new AssertionError("Microsoft identities need no API call");
        }).build();

        assertEquals(new Identity("ada@example.com", "Ada"),
                provider.identity(never, Map.of("access_token", "at", "id_token", idToken(key, "ms-id", true))));
        assertThrows(IllegalStateException.class,
                () -> provider.identity(never, Map.of("access_token", "at", "id_token", idToken(key, "ms-id", false))),
                "a token without xms_edov is an unverified address");
        assertThrows(IllegalStateException.class,
                () -> provider.identity(never, Map.of("access_token", "at", "id_token", idToken(key, "other-app", true))),
                "a token minted for another app must not open a user here");
    }

    private static String idToken(RSAKey key, String audience, boolean verified) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer("https://login.microsoftonline.com/9188040d-6c67-4c5b-b112-36a304b66dad/v2.0")
                .audience(audience).subject("sub-1")
                .expirationTime(new Date(System.currentTimeMillis() + 60_000))
                .claim("email", "ada@example.com").claim("name", "Ada");
        if (verified) {
            claims.claim("xms_edov", true);
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
