package com.demo.auth.provider;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The back channel, over real HTTP: a throwaway server stands in for the provider's token endpoint and API,
 * so the form, the headers and the JSON decoding are the ones a real provider would see and send.
 */
class OAuthFlowIntegrationTests {

    /** A provider whose endpoints are the throwaway server, and whose identity is one API call. */
    static class Local extends OAuthProvider {
        private final String api;

        Local(String base) {
            super("local", "Local", base + "/authorize", base + "/token", "email");
            this.api = base + "/me";
            setEnabled(true);
            setClientId("client-1");
            setClientSecret("secret-1");
        }

        @Override
        public Identity identity(RestClient http, String accessToken) {
            Map<?, ?> me = get(http, api, accessToken, Map.class);
            return new Identity(string(me, "email"), string(me, "name"));
        }
    }

    /** What X looks like: the secret only as HTTP Basic. */
    static final class Basic extends Local {
        Basic(String base) {
            super(base);
        }

        @Override
        public boolean basicClientAuth() {
            return true;
        }
    }

    /** What Apple and Microsoft look like: the identity in the id_token, no API call at all. */
    static final class IdToken extends Local {
        IdToken(String base) {
            super(base);
        }

        @Override
        public Identity identity(RestClient http, Map<?, ?> tokenResponse) {
            Map<String, Object> claims = idTokenClaims(tokenResponse);
            return new Identity(string(claims, "email"), string(claims, "name"));
        }
    }

    private HttpServer server;

    private final Map<String, String> seen = new ConcurrentHashMap<>();

    private volatile String tokenAnswer;

    private volatile int tokenStatus;

    private OAuthFlow flow;

    private Local provider;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/token", exchange -> {
            seen.put("token.form", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            seen.put("token.contentType", exchange.getRequestHeaders().getFirst("Content-Type"));
            seen.put("token.accept", exchange.getRequestHeaders().getFirst("Accept"));
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization != null) {
                seen.put("token.authorization", authorization);
            }
            answer(exchange, tokenStatus, tokenAnswer);
        });
        server.createContext("/me", exchange -> {
            seen.put("me.authorization", exchange.getRequestHeaders().getFirst("Authorization"));
            answer(exchange, 200, "{\"email\":\"ada@example.com\",\"name\":\"Ada\"}");
        });
        server.start();

        String base = "http://localhost:" + server.getAddress().getPort();
        OAuthProperties config = new OAuthProperties();
        config.setRedirectBaseUrl("http://app.example.com");
        provider = new Local(base);
        flow = new OAuthFlow(config, List.of(provider));
        tokenStatus = 200;
        tokenAnswer = "{\"access_token\":\"at-1\",\"token_type\":\"bearer\"}";
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void theCodeAndVerifierBuyAnAccessTokenAndTheTokenBuysTheIdentity() {
        assertEquals(new Identity("ada@example.com", "Ada"), flow.identity(provider, "code-1", "verifier-1"));

        Map<String, String> form = form(seen.get("token.form"));
        assertEquals("authorization_code", form.get("grant_type"));
        assertEquals("code-1", form.get("code"));
        assertEquals("verifier-1", form.get("code_verifier"), "PKCE: the provider checks it against the challenge");
        assertEquals("client-1", form.get("client_id"));
        assertEquals("secret-1", form.get("client_secret"), "the secret travels here, never through the browser");
        assertEquals("http://app.example.com/api/oauth/local/callback", form.get("redirect_uri"),
                "must match the consent request's byte for byte, or the provider refuses the code");
        assertEquals("application/x-www-form-urlencoded", seen.get("token.contentType"));
        assertEquals("application/json", seen.get("token.accept"), "GitHub answers form-encoded unless asked for JSON");
        assertEquals("Bearer at-1", seen.get("me.authorization"));
    }

    @Test
    void anAnswerWithNoAccessTokenOrAnErrorStatusIsARefusal() {
        tokenAnswer = "{\"error\":\"bad_verification_code\"}";
        assertThrows(IllegalStateException.class, () -> flow.identity(provider, "code-1", "verifier-1"),
                "GitHub answers a spent code with 200 and an error field");

        tokenAnswer = "{\"access_token\":\"\"}";
        assertThrows(IllegalStateException.class, () -> flow.identity(provider, "code-1", "verifier-1"));

        tokenStatus = 400;
        tokenAnswer = "{\"error\":\"invalid_grant\"}";
        assertThrows(RestClientException.class, () -> flow.identity(provider, "code-1", "verifier-1"));
        assertNull(seen.get("me.authorization"), "no token, so the API is never called");
    }

    @Test
    void aBasicAuthProviderGetsTheSecretInTheHeaderAndNowhereElse() {
        Basic basic = new Basic("http://localhost:" + server.getAddress().getPort());
        assertEquals(new Identity("ada@example.com", "Ada"), flow.identity(basic, "code-1", "verifier-1"));

        String expected = "Basic " + java.util.Base64.getEncoder().encodeToString("client-1:secret-1".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, seen.get("token.authorization"));
        Map<String, String> form = form(seen.get("token.form"));
        assertNull(form.get("client_secret"), "RFC 6749: one client authentication method per request, and X refuses the form one");
        assertEquals("client-1", form.get("client_id"), "the id still travels in the form, as X's example shows");
        assertEquals("verifier-1", form.get("code_verifier"));
    }

    @Test
    void anIdTokenProviderReadsTheClaimsAndChecksTheyWereMintedForThisClient() throws Exception {
        IdToken provider = new IdToken("http://localhost:" + server.getAddress().getPort());
        com.nimbusds.jose.jwk.RSAKey key = new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).generate();

        tokenAnswer = "{\"access_token\":\"at-1\",\"id_token\":\"" + idToken(key, "client-1", 60) + "\"}";
        assertEquals(new Identity("ada@example.com", "Ada"), flow.identity(provider, "code-1", "verifier-1"));
        assertNull(seen.get("me.authorization"), "the identity is in the token, so no API is asked");

        tokenAnswer = "{\"access_token\":\"at-1\",\"id_token\":\"" + idToken(key, "someone-else", 60) + "\"}";
        assertThrows(IllegalStateException.class, () -> flow.identity(provider, "code-1", "verifier-1"),
                "a token minted for another app must not open a user here");
        tokenAnswer = "{\"access_token\":\"at-1\",\"id_token\":\"" + idToken(key, "client-1", -60) + "\"}";
        assertThrows(IllegalStateException.class, () -> flow.identity(provider, "code-1", "verifier-1"), "nor an expired one");
        tokenAnswer = "{\"access_token\":\"at-1\"}";
        assertThrows(IllegalStateException.class, () -> flow.identity(provider, "code-1", "verifier-1"), "nor none at all");
        tokenAnswer = "{\"access_token\":\"at-1\",\"id_token\":\"not.a.jwt\"}";
        assertThrows(IllegalStateException.class, () -> flow.identity(provider, "code-1", "verifier-1"));
    }

    /** An id_token as a provider would mint it, signed with a throwaway key: the flow trusts TLS, not the signature. */
    static String idToken(com.nimbusds.jose.jwk.RSAKey key, String audience, long secondsToLive) throws Exception {
        com.nimbusds.jwt.SignedJWT jwt = new com.nimbusds.jwt.SignedJWT(
                new com.nimbusds.jose.JWSHeader(com.nimbusds.jose.JWSAlgorithm.RS256),
                new com.nimbusds.jwt.JWTClaimsSet.Builder()
                        .issuer("https://issuer.example.com").audience(audience).subject("sub-1")
                        .expirationTime(new java.util.Date(System.currentTimeMillis() + secondsToLive * 1000))
                        .claim("email", "ada@example.com").claim("name", "Ada").build());
        jwt.sign(new com.nimbusds.jose.crypto.RSASSASigner(key));
        return jwt.serialize();
    }

    private static void answer(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static Map<String, String> form(String body) {
        return Arrays.stream(body.split("&")).map(pair -> pair.split("=", 2)).collect(Collectors.toMap(
                pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                pair -> pair.length > 1 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : ""));
    }
}
