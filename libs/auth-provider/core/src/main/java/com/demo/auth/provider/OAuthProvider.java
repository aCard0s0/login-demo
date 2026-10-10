package com.demo.auth.provider;

import lombok.Getter;
import lombok.Setter;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * One place a caller can sign in with: its endpoints and scope, which are the same for every deployment and
 * so live in code, and its credentials, which differ per deployment and are bound from {@code oauth.<key>.*}.
 *
 * <p>A provider module extends this, declares itself a {@code @Component} bound to its own prefix, and fills
 * in {@link #identity}: how to read a <b>verified</b> email off that provider. Verified is the whole contract --
 * the service matches users by email, so an unverified address would let anyone who can claim it at the
 * provider walk into the user that already owns it.
 */
@Getter
@Setter
public abstract class OAuthProvider {

    private final String key;

    private final String label;

    private final String authorizeUri;

    private final String tokenUri;

    private final String scope;

    private boolean enabled;

    private String clientId;

    private String clientSecret;

    /**
     * @param key   what the provider is called in a URL, in the login button, and in {@code oauth.<key>.*}
     * @param label what the consent screen and the error messages call it
     */
    protected OAuthProvider(String key, String label, String authorizeUri, String tokenUri, String scope) {
        this.key = key;
        this.label = label;
        this.authorizeUri = authorizeUri;
        this.tokenUri = tokenUri;
        this.scope = scope;
    }

    /**
     * The verified identity behind an access token, or an {@link IllegalStateException} whose message is safe
     * to show the person signing in ("your account has no verified email").
     */
    public abstract Identity identity(RestClient http, String accessToken);

    /**
     * Whether this provider can actually be offered. Enabled but unconfigured counts as off: a button that
     * leads to a provider error page is worse than no button, and half-set credentials are the normal state
     * of a .env somebody copied and did not finish.
     */
    public boolean usable() {
        return enabled && notBlank(clientId) && notBlank(clientSecret);
    }

    /** A JSON GET on the provider's API as the token's owner. */
    protected static <T> T get(RestClient http, String uri, String accessToken, Class<T> type) {
        return http.get().uri(uri)
                .header("Authorization", "Bearer " + accessToken)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(type);
    }

    /** A field of a provider answer as text, or empty: provider JSON is never trusted to have a shape. */
    protected static String string(Map<?, ?> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
