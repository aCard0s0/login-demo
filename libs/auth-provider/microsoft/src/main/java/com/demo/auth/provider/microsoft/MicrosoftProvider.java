package com.demo.auth.provider.microsoft;

import com.demo.auth.provider.Identity;
import com.demo.auth.provider.OAuthProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Sign in with Microsoft: personal accounts and Entra ID work accounts through the {@code common} endpoint.
 *
 * <p>The identity comes from the ID token, not from Graph, because Graph says nothing about whether an
 * address is verified. Neither does the ID token's {@code email} claim on its own: a tenant admin can set
 * any address on a user they control (the "nOAuth" account takeover). Microsoft's answer is the optional
 * {@code xms_edov} claim -- true only when the tenant owns the address's domain, or for a personal account
 * whose address was verified at sign-up -- so that is what is required here. The app registration must ask
 * for it (Token configuration -> add optional claim -> {@code email}, then {@code xms_edov} on the manifest);
 * without it every Microsoft sign-in is refused as unverified, which is the safe way round.
 */
// ponytail: the common tenant, so personal and work accounts both sign in. Restricting to one tenant means
// a tenant property and the endpoints built from it rather than fixed in the constructor.
@Component
@ConfigurationProperties("oauth.microsoft")
public class MicrosoftProvider extends OAuthProvider {

    public MicrosoftProvider() {
        super("microsoft", "Microsoft",
                "https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
                "https://login.microsoftonline.com/common/oauth2/v2.0/token",
                "openid email profile");
    }

    /** Never called: the identity is in the ID token, and {@link #identity(RestClient, Map)} reads it there. */
    @Override
    public Identity identity(RestClient http, String accessToken) {
        throw new UnsupportedOperationException("Microsoft identities come from the ID token");
    }

    @Override
    public Identity identity(RestClient http, Map<?, ?> tokenResponse) {
        return identity(idTokenClaims(tokenResponse));
    }

    /** Only an address Microsoft vouches for through {@code xms_edov}; the plain email claim is anyone's to set. */
    static Identity identity(Map<?, ?> claims) {
        String email = string(claims, "email");
        if (email.isBlank() || !flag(claims, "xms_edov")) {
            throw new IllegalStateException("your Microsoft account has no verified email address");
        }
        return new Identity(email, string(claims, "name"));
    }
}
