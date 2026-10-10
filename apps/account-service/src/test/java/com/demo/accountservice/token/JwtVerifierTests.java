package com.demo.accountservice.token;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The only thing standing between a stranger and somebody's money, so it is checked against a real JWKS
 * endpoint rather than a mock: a throwaway HTTP server publishing the public half of a key made here.
 */
class JwtVerifierTests {

    @Test
    void takesATokenFromTheAdvertisedKeyAndNothingElse() throws Exception {
        RSAKey advertised = new RSAKeyGenerator(2048).keyID("advertised").generate();
        RSAKey impostor = new RSAKeyGenerator(2048).keyID("impostor").generate();

        HttpServer jwks = publish(Map.of(
                "/jwks.json", new JWKSet(advertised.toPublicJWK()).toString(),
                "/token-versions", "{\"7\":2}"));
        try {
            String base = "http://localhost:" + jwks.getAddress().getPort();
            JwtVerifier verifier = new JwtVerifier(base + "/jwks.json", new Revocations(base + "/token-versions"));
            Instant later = Instant.now().plusSeconds(300);

            Caller caller = verifier.callerOf("Bearer " + token(advertised, "42", later, "MODERATOR"));
            assertEquals("42", caller.accountId());
            assertTrue(caller.readsEveryone());
            assertFalse(caller.writesEveryone());
            assertNull(caller.agentId(), "only an agent token is pinned to an agent");

            // Nothing is obliged to put a role in a token, so its absence has to mean the smallest one.
            Caller roleless = verifier.callerOf("Bearer " + token(advertised, "42", later, null));
            assertEquals("USER", roleless.role());
            assertFalse(roleless.readsEveryone());

            // An agent token is pinned to the one agent in its claim; one that names none is refused, not unpinned.
            Caller agent = verifier.callerOf("Bearer " + token(advertised, "42", later, "AGENT", null, 9L));
            assertEquals(9L, agent.agentId());
            assertTrue(agent.isAgent());
            assertEquals("agent 9", agent.describe());
            assertThrows(ResponseStatusException.class, () -> verifier.callerOf("Bearer " + token(advertised, "42", later, "AGENT")),
                    "an AGENT token with no agent claim would otherwise get the owner's whole reach");
            assertNull(verifier.callerOf("Bearer " + token(advertised, "42", later, "USER", null, 9L)).agentId(),
                    "an agent claim on anything but an AGENT token pins nothing");

            assertThrows(ResponseStatusException.class, () -> verifier.callerOf(token(impostor, "42", later, "ADMIN")),
                    "a token signed by a key auth-service never published must not be accepted");
            // Past the 60s of clock skew the claims verifier allows by default.
            assertThrows(ResponseStatusException.class,
                    () -> verifier.callerOf("Bearer " + token(advertised, "42", Instant.now().minusSeconds(300), "USER")),
                    "an expired token must not be accepted");
            // Account 7 was revoked up to version 2: older tokens die, that one and anything newer live.
            assertThrows(ResponseStatusException.class, () -> verifier.callerOf("Bearer " + token(advertised, "7", later, "USER", 1)),
                    "a token from before the account's last revocation must not be accepted");
            assertThrows(ResponseStatusException.class, () -> verifier.callerOf("Bearer " + token(advertised, "7", later, "USER")),
                    "a token with no version is version zero");
            assertThrows(ResponseStatusException.class, () -> verifier.callerOf("Bearer " + token(advertised, "7", later, "AGENT", 1, 9L)),
                    "revoking the owner kills their agents' tokens too");
            assertEquals("7", verifier.callerOf("Bearer " + token(advertised, "7", later, "USER", 2)).accountId());
            assertEquals("7", verifier.callerOf("Bearer " + token(advertised, "7", later, "USER", 3)).accountId(),
                    "a token newer than the last revocation heard of is fine");
            assertThrows(ResponseStatusException.class, () -> verifier.callerOf(null));
            assertThrows(ResponseStatusException.class, () -> verifier.callerOf("Bearer not.a.token"));
        } finally {
            jwks.stop(0);
        }
    }

    private static String token(RSAKey key, String subject, Instant expiry, String role) throws Exception {
        return token(key, subject, expiry, role, null);
    }

    private static String token(RSAKey key, String subject, Instant expiry, String role, Integer version) throws Exception {
        return token(key, subject, expiry, role, version, null);
    }

    private static String token(RSAKey key, String subject, Instant expiry, String role, Integer version, Long agent) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().subject(subject).expirationTime(Date.from(expiry));
        if (agent != null) {
            claims.claim("agent", agent);
        }
        if (role != null) {
            claims.claim("role", role);
        }
        if (version != null) {
            claims.claim("ver", version);
        }
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims.build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    /** A JSON server on a free port, standing in for auth-service: path to body. */
    private static HttpServer publish(Map<String, String> bodies) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        bodies.forEach((path, json) -> server.createContext(path, exchange -> {
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }));
        server.start();
        return server;
    }
}
