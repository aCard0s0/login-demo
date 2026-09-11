package com.demo.authservice.token;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues and checks the RS256 tokens that identify a caller.
 *
 * <p>The keypair is generated at startup and never written to disk: auth-service is the only holder of the
 * private half, and every other service reads the public half from the JWKS endpoint. Asymmetric rather than
 * a shared secret, so a service that only needs to verify tokens is never able to mint them.
 *
 * <p>A restart mints a new key and so invalidates every token in flight, which is the same thing the old
 * in-memory session map did.
 */
// ponytail: one key, no rotation. Tokens carry a kid, so adding a second key later is additive.
@Component
public class Tokens {

    private final RSAKey key;

    private final Duration ttl;

    public Tokens(@Value("${auth.token-ttl:30m}") Duration ttl) throws Exception {
        this.ttl = ttl;
        this.key = new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
    }

    /**
     * A signed token naming the account. The id is the subject because it is the one thing that never changes.
     *
     * <p>Takes the fields rather than the Account itself, so this package stays free of any dependency on
     * the account package and the arrow between them only ever points one way. The role rides along as a
     * plain string: it is what lets todo-service decide what a caller may touch without asking us.
     */
    public String issue(Long accountId, String email, String name, String role) {
        Instant now = Instant.now();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                new JWTClaimsSet.Builder()
                        .subject(String.valueOf(accountId))
                        .claim("email", email)
                        .claim("name", name)
                        .claim("role", role)
                        .issueTime(Date.from(now))
                        .expirationTime(Date.from(now.plus(ttl)))
                        .build());
        try {
            jwt.sign(new RSASSASigner(key));
        } catch (Exception e) {
            throw new IllegalStateException("could not sign a token", e);
        }
        return jwt.serialize();
    }

    /** The account id a token names, or empty if the signature is wrong, the token is malformed, or it has expired. */
    public Optional<Long> accountIdFrom(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!jwt.verify(new RSASSAVerifier(key.toPublicJWK()))) {
                return Optional.empty();
            }
            Date expiry = jwt.getJWTClaimsSet().getExpirationTime();
            if (expiry == null || expiry.toInstant().isBefore(Instant.now())) {
                return Optional.empty();
            }
            return Optional.of(Long.valueOf(jwt.getJWTClaimsSet().getSubject()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** The public half, in the shape the JWKS endpoint serves and other services know how to read. */
    public Map<String, Object> publicJwks() {
        return new JWKSet(key.toPublicJWK()).toJSONObject();
    }
}
