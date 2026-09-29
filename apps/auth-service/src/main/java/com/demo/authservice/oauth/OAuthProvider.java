package com.demo.authservice.oauth;

import java.util.Optional;

/**
 * The providers this service can log a caller in with, and the three URLs each of them answers on.
 *
 * <p>Endpoints rather than credentials: what is here is the same for every deployment and so belongs in
 * code, while the client id and secret differ per deployment and come from {@link OAuthProperties}.
 *
 * <p>Deliberately an enum rather than a registry bound from configuration. Adding a provider means teaching
 * {@link OAuthService} how to read its userinfo shape anyway, so a provider can never be only configuration.
 */
public enum OAuthProvider {

    GOOGLE("https://accounts.google.com/o/oauth2/v2/auth",
            "https://oauth2.googleapis.com/token",
            "https://openidconnect.googleapis.com/v1/userinfo",
            "openid email profile"),

    /** user:email is what makes the private-address fallback in {@link OAuthService} readable. */
    GITHUB("https://github.com/login/oauth/authorize",
            "https://github.com/login/oauth/access_token",
            "https://api.github.com/user",
            "read:user user:email");

    private final String authorizeUri;

    private final String tokenUri;

    private final String userinfoUri;

    private final String scope;

    OAuthProvider(String authorizeUri, String tokenUri, String userinfoUri, String scope) {
        this.authorizeUri = authorizeUri;
        this.tokenUri = tokenUri;
        this.userinfoUri = userinfoUri;
        this.scope = scope;
    }

    public String authorizeUri() {
        return authorizeUri;
    }

    public String tokenUri() {
        return tokenUri;
    }

    public String userinfoUri() {
        return userinfoUri;
    }

    public String scope() {
        return scope;
    }

    /** What the provider is called in a URL and in the frontend's button. */
    public String key() {
        return name().toLowerCase();
    }

    /** The provider a path segment names, or empty. Empty rather than an exception: the segment is user input. */
    public static Optional<OAuthProvider> of(String key) {
        for (OAuthProvider provider : values()) {
            if (provider.key().equalsIgnoreCase(key == null ? "" : key.strip())) {
                return Optional.of(provider);
            }
        }
        return Optional.empty();
    }
}
