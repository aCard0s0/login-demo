package com.demo.auth.provider;

import com.nimbusds.jwt.SignedJWT;
import lombok.Getter;
import lombok.Setter;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * One place a caller can sign in with: its endpoints and scope, which are the same for every deployment and
 * so live in code, and its credentials, which differ per deployment and are bound from {@code oauth.<key>.*}.
 *
 * <p>A provider module extends this, declares itself a {@code @Component} bound to its own prefix, and fills
 * in {@link #identity(RestClient, String)}: how to read a <b>verified</b> email off that provider. Verified is
 * the whole contract -- the service matches users by email, so an unverified address would let anyone who can
 * claim it at the provider walk into the user that already owns it.
 *
 * <p>A provider that answers with an OpenID Connect ID token and no userinfo worth asking (Apple, Microsoft)
 * overrides {@link #identity(RestClient, Map)} instead and reads the claims through {@link #idTokenClaims}.
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
     * The verified identity behind the whole token-endpoint answer. The default hands the access token to
     * {@link #identity(RestClient, String)}; a provider that puts the identity in an {@code id_token} overrides
     * this one and never calls an API at all.
     */
    public Identity identity(RestClient http, Map<?, ?> tokenResponse) {
        return identity(http, string(tokenResponse, "access_token"));
    }

    /**
     * Whether this provider can actually be offered. Enabled but unconfigured counts as off: a button that
     * leads to a provider error page is worse than no button, and half-set credentials are the normal state
     * of a .env somebody copied and did not finish.
     */
    public boolean usable() {
        return enabled && notBlank(clientId) && notBlank(clientSecret);
    }

    /**
     * Whether the token endpoint wants the client credentials as HTTP Basic rather than form fields. RFC 6749
     * makes Basic the one a server must support, but Google and GitHub only read the form, and X only reads
     * Basic -- so it is the provider's call.
     */
    public boolean basicClientAuth() {
        return false;
    }

    /**
     * Whether the provider sends the browser back with a POST ({@code response_mode=form_post}) rather than
     * a GET with a query string. Apple insists on it whenever the name or email scope is asked for. The
     * callback's cookies must then be {@code SameSite=None}, since a cross-site POST carries no Lax cookie.
     */
    public boolean formPost() {
        return false;
    }

    /** A JSON GET on the provider's API as the token's owner. */
    protected static <T> T get(RestClient http, String uri, String accessToken, Class<T> type) {
        return http.get().uri(uri)
                .header("Authorization", "Bearer " + accessToken)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(type);
    }

    /**
     * The claims of the {@code id_token} in a token-endpoint answer, after checking it was minted for this
     * client and has not expired. The signature is deliberately not checked: the token arrived over TLS
     * straight from the provider's token endpoint, which OpenID Connect Core 3.1.3.7 accepts in place of a
     * signature check. The audience check is what stops a token minted for some other app being replayed here.
     */
    protected Map<String, Object> idTokenClaims(Map<?, ?> tokenResponse) {
        String idToken = string(tokenResponse, "id_token");
        if (idToken.isBlank()) {
            throw new IllegalStateException(label + " did not return an identity token");
        }
        try {
            var claims = SignedJWT.parse(idToken).getJWTClaimsSet();
            List<String> audience = claims.getAudience();
            if (audience == null || !audience.contains(clientId)) {
                throw new IllegalStateException(label + " returned an identity token for another app");
            }
            Date expiry = claims.getExpirationTime();
            if (expiry == null || expiry.before(new Date())) {
                throw new IllegalStateException(label + " returned an expired identity token");
            }
            return claims.getClaims();
        } catch (java.text.ParseException e) {
            throw new IllegalStateException(label + " returned an unreadable identity token", e);
        }
    }

    /** A field of a provider answer as text, or empty: provider JSON is never trusted to have a shape. */
    protected static String string(Map<?, ?> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    /** A boolean a provider may spell as {@code true}, {@code "true"} or {@code "1"}; anything else is no. */
    protected static boolean flag(Map<?, ?> body, String key) {
        String value = string(body, key);
        return Boolean.parseBoolean(value) || "1".equals(value);
    }

    protected static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
