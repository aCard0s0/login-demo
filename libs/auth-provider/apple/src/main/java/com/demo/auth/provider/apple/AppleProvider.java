package com.demo.auth.provider.apple;

import com.demo.auth.provider.Identity;
import com.demo.auth.provider.OAuthProvider;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

/**
 * Sign in with Apple. Three things set it apart from every other provider here:
 *
 * <ul>
 *   <li>There is no client secret to copy. Apple hands out a private key (a {@code .p8} file) and the client
 *       secret is a short-lived JWT signed with it, minted fresh for every token request. So this provider
 *       binds {@code team-id}, {@code key-id} and {@code private-key} instead of {@code client-secret}; the
 *       {@code client-id} is the Services ID.</li>
 *   <li>Apple only sends the browser back with a POST ({@code response_mode=form_post}) when the email is
 *       asked for, which is why {@link #formPost()} is true and the callback must take a POST.</li>
 *   <li>There is no userinfo endpoint. The verified email is in the ID token, and the name arrives once,
 *       on the very first consent, in a {@code user} form field that is never sent again.</li>
 * </ul>
 *
 * Apple also refuses any redirect URI that is not HTTPS on a real domain -- {@code localhost} included --
 * so this provider only works on a deployed address.
 */
// ponytail: the first-consent `user` form field (the only place Apple ever sends the name) is not read, so an
// Apple user is named after the address's local part until they rename themselves. Reading it means handing
// the callback's form fields to the provider, which no other provider needs.
@Component
@ConfigurationProperties("oauth.apple")
@Getter
@Setter
public class AppleProvider extends OAuthProvider {

    static final String AUDIENCE = "https://appleid.apple.com";

    /** Apple allows up to six months; one request's worth is plenty, since every request mints its own. */
    private static final Duration SECRET_TTL = Duration.ofMinutes(5);

    /** The 10-character Team ID from the developer account, the {@code iss} of the client secret. */
    private String teamId;

    /** The 10-character Key ID of the Sign in with Apple key, the {@code kid} of the client secret. */
    private String keyId;

    /** The {@code .p8} file's contents: PEM with or without its BEGIN/END lines, newlines real or {@code \n}. */
    private String privateKey;

    public AppleProvider() {
        super("apple", "Apple",
                "https://appleid.apple.com/auth/authorize",
                "https://appleid.apple.com/auth/token",
                "name email");
    }

    /** All four halves, and a key that actually parses: a mangled .p8 in .env counts as off, not as a broken button. */
    @Override
    public boolean usable() {
        if (!isEnabled() || !notBlank(getClientId()) || !notBlank(teamId) || !notBlank(keyId) || !notBlank(privateKey)) {
            return false;
        }
        try {
            signingKey();
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /** The client secret Apple wants: a JWT naming the team, the key, and this Services ID, signed with the .p8 key. */
    @Override
    public String getClientSecret() {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(teamId)
                .subject(getClientId())
                .audience(AUDIENCE)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(SECRET_TTL)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(keyId).build(), claims);
        try {
            jwt.sign(new ECDSASigner(signingKey()));
        } catch (JOSEException e) {
            throw new IllegalStateException("could not sign the Apple client secret", e);
        }
        return jwt.serialize();
    }

    @Override
    public boolean formPost() {
        return true;
    }

    /** Never called: the identity is in the ID token, and {@link #identity(RestClient, Map)} reads it there. */
    @Override
    public Identity identity(RestClient http, String accessToken) {
        throw new UnsupportedOperationException("Apple identities come from the ID token");
    }

    @Override
    public Identity identity(RestClient http, Map<?, ?> tokenResponse) {
        return identity(idTokenClaims(tokenResponse));
    }

    /** Apple states {@code email_verified}, as a boolean or the string "true"; a private relay address is still verified. */
    static Identity identity(Map<?, ?> claims) {
        String email = string(claims, "email");
        if (email.isBlank() || !flag(claims, "email_verified")) {
            throw new IllegalStateException("your Apple ID has no verified email address");
        }
        return new Identity(email, "");
    }

    /** The .p8 contents as a key, however .env mangled them: PEM lines, literal {@code \n}, or bare base64. */
    ECPrivateKey signingKey() {
        String base64 = privateKey.replace("\\n", "\n")
                .replaceAll("-----[A-Z ]+-----", "")
                .replaceAll("\\s", "");
        try {
            return (ECPrivateKey) KeyFactory.getInstance("EC")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (Exception e) {
            throw new IllegalStateException("oauth.apple.private-key is not a PKCS#8 EC private key", e);
        }
    }
}
