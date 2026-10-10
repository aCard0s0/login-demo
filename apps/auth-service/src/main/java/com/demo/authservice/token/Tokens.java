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
import org.springframework.beans.factory.annotation.Autowired;
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

    private final RSASSAVerifier verifier;

    private final Duration ttl;

    private final Duration agentTtl;

    /** Login tokens of the given life, agent tokens of the default 30 days. */
    public Tokens(Duration ttl) throws Exception {
        this(ttl, Duration.ofDays(30));
    }

    @Autowired
    public Tokens(@Value("${auth.token-ttl:30m}") Duration ttl,
                  @Value("${auth.agent-token-ttl:30d}") Duration agentTtl) throws Exception {
        this.ttl = ttl;
        this.agentTtl = agentTtl;
        this.key = new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
        this.verifier = new RSASSAVerifier(key.toPublicJWK());
    }

    /**
     * A signed token naming the account. The id is the subject because it is the one thing that never changes.
     *
     * <p>Takes the fields rather than the Account itself, so this package stays free of any dependency on
     * the account package and the arrow between them only ever points one way. The role rides along as a
     * plain string: it is what lets todo-service decide what a caller may touch without asking us.
     */
    public String issue(Long accountId, String email, String name, String role, int version) {
        return issue(accountId, email, name, role, version, Map.of(), ttl);
    }

    /**
     * A long-lived token for one of the account's agents to connect to agent-service with: the account as
     * subject, the {@code AGENT} role, and the agent's id as a claim so agent-service can pin it to that one
     * agent. It carries the account's current token version like any other, so "revoke access" kills it too,
     * and the agent's own version as {@code agentVer}, so the owner can kill this one agent's tokens alone.
     */
    public String issueForAgent(Long accountId, String email, String name, int version, Long agentId, int agentVersion) {
        return issue(accountId, email, name, "AGENT", version, Map.of("agent", agentId, "agentVer", agentVersion), agentTtl);
    }

    private String issue(Long accountId, String email, String name, String role, int version,
                         Map<String, Object> extra, Duration lifetime) {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .subject(String.valueOf(accountId))
                .claim("email", email)
                .claim("name", name)
                .claim("role", role)
                .claim("ver", version)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(lifetime)));
        extra.forEach(claims::claim);
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims.build());
        try {
            jwt.sign(new RSASSASigner(key));
        } catch (Exception e) {
            throw new IllegalStateException("could not sign a token", e);
        }
        return jwt.serialize();
    }

    /** What a verified token says about its account: which one, which generation of its tokens, and whether it was minted for an agent. */
    public record Claims(Long accountId, int version, boolean agent) {}

    /** What a token names, or empty if the signature is wrong, the token is malformed, or it has expired. */
    public Optional<Claims> claimsFrom(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!jwt.verify(verifier)) {
                return Optional.empty();
            }
            Date expiry = jwt.getJWTClaimsSet().getExpirationTime();
            if (expiry == null || expiry.toInstant().isBefore(Instant.now())) {
                return Optional.empty();
            }
            Long version = jwt.getJWTClaimsSet().getLongClaim("ver");
            return Optional.of(new Claims(Long.valueOf(jwt.getJWTClaimsSet().getSubject()),
                    version == null ? 0 : version.intValue(), "AGENT".equals(jwt.getJWTClaimsSet().getStringClaim("role"))));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** The public half, in the shape the JWKS endpoint serves and other services know how to read. */
    public Map<String, Object> publicJwks() {
        return new JWKSet(key.toPublicJWK()).toJSONObject();
    }
}
