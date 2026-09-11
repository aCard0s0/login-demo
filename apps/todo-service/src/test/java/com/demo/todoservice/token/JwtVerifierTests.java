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
import static org.junit.jupiter.api.Assertions.assertThrows;

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

            assertEquals("42", verifier.ownerOf("Bearer " + token(advertised, "42", later)));

            assertThrows(ResponseStatusException.class, () -> verifier.ownerOf(token(impostor, "42", later)),
                    "a token signed by a key auth-service never published must not be accepted");
            // Past the 60s of clock skew the claims verifier allows by default.
            assertThrows(ResponseStatusException.class,
                    () -> verifier.ownerOf("Bearer " + token(advertised, "42", Instant.now().minusSeconds(300))),
                    "an expired token must not be accepted");
            assertThrows(ResponseStatusException.class, () -> verifier.ownerOf(null));
            assertThrows(ResponseStatusException.class, () -> verifier.ownerOf("Bearer not.a.token"));
        } finally {
            jwks.stop(0);
        }
    }

    private static String token(RSAKey key, String subject, Instant expiry) throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                new JWTClaimsSet.Builder().subject(subject).expirationTime(Date.from(expiry)).build());
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
