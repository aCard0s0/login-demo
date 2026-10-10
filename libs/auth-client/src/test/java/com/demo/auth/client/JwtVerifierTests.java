package com.demo.auth.client;

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
 * The only thing standing between a stranger and somebody's todos, agents or money, so it is checked against
 * a real JWKS endpoint rather than a mock: a throwaway HTTP server publishing the public half of a key made here.
 */
class JwtVerifierTests {

    @Test
    void takesATokenFromTheAdvertisedKeyAndNothingElse() throws Exception {
        RSAKey advertised = new RSAKeyGenerator(2048).keyID("advertised").generate();
        RSAKey impostor = new RSAKeyGenerator(2048).keyID("impostor").generate();

        HttpServer jwks = publish(Map.of(
                "/jwks.json", new JWKSet(advertised.toPublicJWK()).toString(),
                "/token-versions", "{\"7\":2,\"agent:9\":1}"));
        try {
            String base = "http://localhost:" + jwks.getAddress().getPort();
            JwtVerifier verifier = new JwtVerifier(base + "/jwks.json", new Revocations(base + "/token-versions"));
            Instant later = Instant.now().plusSeconds(300);

            Caller caller = verifier.callerOf("Bearer " + token(advertised, "42", later, "MODERATOR"));
            assertEquals("42", caller.userId());
            assertEquals("MODERATOR", caller.role());
            assertTrue(caller.readsEveryone());
            assertFalse(caller.writesEveryone(), "only an admin writes everyone");
            assertNull(caller.agentId(), "only an agent token is pinned to an agent");
            assertFalse(caller.isAgent());
            assertTrue(caller.mayActAs(5L));
            assertEquals("user 42", caller.describe());

            // Nothing is obliged to put a role in a token, so its absence has to mean the smallest one.
            Caller roleless = verifier.callerOf("Bearer " + token(advertised, "42", later, null));
            assertEquals("USER", roleless.role());
            assertFalse(roleless.readsEveryone());

            // An agent token is pinned to the one agent in its claim; one that names none is refused, not unpinned.
            Caller agent = verifier.callerOf("Bearer " + token(advertised, "42", later, "AGENT", null, 9L, 1));
            assertEquals(9L, agent.agentId());
            assertTrue(agent.isAgent());
            assertTrue(agent.mayActAs(9L));
            assertFalse(agent.mayActAs(10L));
            assertEquals("agent 9", agent.describe());
            assertThrows(ResponseStatusException.class, () -> verifier.callerOf("Bearer " + token(advertised, "42", later, "AGENT", null, null, 1)),
                    "an AGENT token with no agent claim would otherwise act as every agent of the owner");
            assertNull(verifier.callerOf("Bearer " + token(advertised, "42", later, "USER", null, 9L, 1)).agentId(),
                    "an agent claim on anything but an AGENT token pins nothing");

            // Agent 9's tokens were revoked once, alone: its older token dies, agent 10's and the owner's login live on.
            assertThrows(ResponseStatusException.class, () -> verifier.callerOf("Bearer " + token(advertised, "42", later, "AGENT", null, 9L, 0)),
                    "a token from before the agent's own revocation must not be accepted");
            assertEquals(10L, verifier.callerOf("Bearer " + token(advertised, "42", later, "AGENT", null, 10L, 0)).agentId(),
                    "revoking one agent must not touch another's");
            assertEquals("42", verifier.callerOf("Bearer " + token(advertised, "42", later, "USER")).userId(),
                    "nor the owner's login token");
            assertThrows(ResponseStatusException.class, () -> verifier.callerOf("Bearer " + token(advertised, "42", later, "AGENT", null, 10L, null)),
                    "an agent token minted before agentVer existed cannot be told from a revoked one, so it is refused");

            assertThrows(ResponseStatusException.class, () -> verifier.callerOf(token(impostor, "42", later, "ADMIN")),
                    "a token signed by a key auth-service never published must not be accepted");
            // Past the 60s of clock skew the claims verifier allows by default.
            assertThrows(ResponseStatusException.class,
                    () -> verifier.callerOf("Bearer " + token(advertised, "42", Instant.now().minusSeconds(300), "USER")),
                    "an expired token must not be accepted");
            // User 7 was revoked up to version 2: older tokens die, that one and anything newer live.
            assertThrows(ResponseStatusException.class, () -> verifier.callerOf("Bearer " + token(advertised, "7", later, "USER", 1)),
                    "a token from before the user's last revocation must not be accepted");
            assertThrows(ResponseStatusException.class, () -> verifier.callerOf("Bearer " + token(advertised, "7", later, "USER")),
                    "a token with no version is version zero");
            assertThrows(ResponseStatusException.class, () -> verifier.callerOf("Bearer " + token(advertised, "7", later, "AGENT", 1, 9L, 1)),
                    "revoking the owner kills their agents' tokens too");
            assertEquals("7", verifier.callerOf("Bearer " + token(advertised, "7", later, "USER", 2)).userId());
            assertEquals("7", verifier.callerOf("Bearer " + token(advertised, "7", later, "USER", 3)).userId(),
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
        return token(key, subject, expiry, role, version, null, null);
    }

    private static String token(RSAKey key, String subject, Instant expiry, String role, Integer version, Long agent, Integer agentVersion) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().subject(subject).expirationTime(Date.from(expiry));
        if (agent != null) {
            claims.claim("agent", agent);
        }
        if (agentVersion != null) {
            claims.claim("agentVer", agentVersion);
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
