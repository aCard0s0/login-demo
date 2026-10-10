package com.demo.auth.provider.x;

import com.demo.auth.provider.Identity;
import com.demo.auth.provider.OAuthProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Sign in with X. OAuth 2.0 with PKCE, which X requires rather than merely accepts, and the client secret
 * as HTTP Basic on the token request, which is the only place X reads it.
 *
 * <p>The address is {@code confirmed_email} on {@code /2/users/me}: confirmed is X's word for verified, and
 * it is the only email X ever hands out. It needs the {@code users.email} scope, and the app must have
 * "Request email from users" switched on in the developer portal -- without either the field is simply
 * absent, and the sign-in is refused.
 */
@Component
@ConfigurationProperties("oauth.x")
public class XProvider extends OAuthProvider {

    static final String ME_URI = "https://api.x.com/2/users/me?user.fields=confirmed_email";

    public XProvider() {
        super("x", "X",
                "https://x.com/i/oauth2/authorize",
                "https://api.x.com/2/oauth2/token",
                "tweet.read users.read users.email");
    }

    @Override
    public boolean basicClientAuth() {
        return true;
    }

    @Override
    public Identity identity(RestClient http, String accessToken) {
        return identity(get(http, ME_URI, accessToken, Map.class));
    }

    /** The confirmed address, and the display name with the handle as the fallback every account has. */
    static Identity identity(Map<?, ?> me) {
        Object data = me == null ? null : me.get("data");
        Map<?, ?> user = data instanceof Map<?, ?> map ? map : Map.of();
        String email = string(user, "confirmed_email");
        if (email.isBlank()) {
            throw new IllegalStateException("your X account has no confirmed email address to share");
        }
        String name = string(user, "name");
        return new Identity(email, name.isBlank() ? string(user, "username") : name);
    }
}
