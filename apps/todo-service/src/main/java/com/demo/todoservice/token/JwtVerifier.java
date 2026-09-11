package com.demo.todoservice.token;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
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
 * cached; because the signing is asymmetric, todo-service holds only the public half and could never mint a
 * token of its own. The key selector is pinned to RS256, so a token that asks for "none" or a symmetric
 * algorithm is rejected before its signature is ever looked at.
 */
@Component
public class JwtVerifier {

    private final JWTProcessor<SecurityContext> jwt;

    public JwtVerifier(@Value("${auth.jwks-uri}") String jwksUri) throws Exception {
        JWKSource<SecurityContext> keys = JWKSourceBuilder.create(URI.create(jwksUri).toURL()).build();
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys));
        // Rejects a token with no expiry outright, rather than treating it as one that never expires.
        processor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(null, Set.of("sub", "exp")));
        this.jwt = processor;
    }

    /** The account id behind the Authorization header, or 401 if the token does not check out. */
    public String ownerOf(String authorization) {
        String token = authorization == null ? "" : authorization.replaceFirst("(?i)^Bearer ", "");
        try {
            return jwt.process(token, null).getSubject();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid or expired token");
        }
    }
}
