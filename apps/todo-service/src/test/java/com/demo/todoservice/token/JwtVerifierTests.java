package com.demo.todoservice.token;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The only thing standing between a stranger and somebody's todo list, so it is checked against a real JWKS
 * endpoint rather than a mock: a throwaway HTTP server publishing the public half of a key made here.
 */
class JwtVerifierTests {

    @Test
    void takesATokenFromTheAdvertisedKeyAndNothingElse() throws Exception {
        RSAKey advertised = new RSAKeyGenerator(2048).keyID("advertised").generate();
        RSAKey impostor = new RSAKeyGenerator(2048).keyID("impostor").generate();

        HttpServer jwks = publish(new JWKSet(advertised.toPublicJWK()).toString());
        try {
            JwtVerifier verifier = new JwtVerifier("http://localhost:" + jwks.getAddress().getPort() + "/jwks.json");
            Instant later = Instant.now().plusSeconds(300);

            Caller caller = verifier.callerOf("Bearer " + token(advertised, "42", later, "MODERATOR"));
            assertEquals("42", caller.accountId());
            assertEquals("MODERATOR", caller.role());
            assertTrue(caller.readsEveryone());
            assertFalse(caller.writesEveryone(), "only an admin writes everyone");

            // Nothing is obliged to put a role in a token, so its absence has to mean the smallest one.
            Caller roleless = verifier.callerOf("Bearer " + token(advertised, "42", later, null));
            assertEquals("USER", roleless.role());
            assertFalse(roleless.readsEveryone());

            assertThrows(ResponseStatusException.class, () -> verifier.callerOf(token(impostor, "42", later, "ADMIN")),
                    "a token signed by a key auth-service never published must not be accepted");
            // Past the 60s of clock skew the claims verifier allows by default.
            assertThrows(ResponseStatusException.class,
                    () -> verifier.callerOf("Bearer " + token(advertised, "42", Instant.now().minusSeconds(300), "USER")),
                    "an expired token must not be accepted");
            assertThrows(ResponseStatusException.class, () -> verifier.callerOf(null));
            assertThrows(ResponseStatusException.class, () -> verifier.callerOf("Bearer not.a.token"));
        } finally {
            jwks.stop(0);
        }
    }

    private static String token(RSAKey key, String subject, Instant expiry, String role) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().subject(subject).expirationTime(Date.from(expiry));
        if (role != null) {
            claims.claim("role", role);
        }
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims.build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    /** A one-endpoint JWKS server on a free port, standing in for auth-service. */
    private static HttpServer publish(String jwks) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/jwks.json", exchange -> {
            byte[] body = jwks.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return server;
    }
}
