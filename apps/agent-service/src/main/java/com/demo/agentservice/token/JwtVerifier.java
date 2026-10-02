package com.demo.agentservice.token;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.nimbusds.jwt.proc.JWTProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.Set;

/**
 * Checks the caller's token against auth-service's public key, in process.
 *
 * <p>This replaces a call to auth-service on every single request. The key comes from its JWKS endpoint and is
 * cached; because the signing is asymmetric, agent-service holds only the public half and could never mint a
 * token of its own. The key selector is pinned to RS256, so a token that asks for "none" or a symmetric
 * algorithm is rejected before its signature is ever looked at.
 */
@Component
public class JwtVerifier {

    private final JWTProcessor<SecurityContext> jwt;

    private final Revocations revocations;

    public JwtVerifier(@Value("${auth.jwks-uri}") String jwksUri, Revocations revocations) throws Exception {
        this.revocations = revocations;
        JWKSource<SecurityContext> keys = JWKSourceBuilder.create(URI.create(jwksUri).toURL()).build();
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys));
        // Rejects a token with no expiry outright, rather than treating it as one that never expires.
        processor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(null, Set.of("sub", "exp")));
        this.jwt = processor;
    }

    /** Who is behind the Authorization header, or 401 if the token does not check out. */
    public Caller callerOf(String authorization) {
        String token = authorization == null ? "" : authorization.replaceFirst("(?i)^Bearer ", "");
        try {
            JWTClaimsSet claims = jwt.process(token, null);
            // A token older than the account's last revocation is dead, however good its signature.
            // At least rather than equal: a token minted after a revocation we have not heard of yet is fine.
            Long version = claims.getLongClaim("ver");
            if ((version == null ? 0 : version) < revocations.minimumVersion(claims.getSubject())) {
                throw new IllegalStateException("revoked");
            }
            // A token with no role claim is read as a plain user: least privilege, rather than a 500.
            Object role = claims.getClaim("role");
            // An agent token carries the one agent it was minted for; any other token carries none.
            Long agent = "AGENT".equals(role) ? claims.getLongClaim("agent") : null;
            // Without the claim the pin would silently become "every agent of the owner": refuse instead.
            if ("AGENT".equals(role) && agent == null) {
                throw new IllegalStateException("agent token names no agent");
            }
            return new Caller(claims.getSubject(), role == null ? "USER" : String.valueOf(role), agent);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid or expired token");
        }
    }
}
