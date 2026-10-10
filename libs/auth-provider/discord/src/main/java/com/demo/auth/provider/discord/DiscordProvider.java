package com.demo.auth.provider.discord;

import com.demo.auth.provider.Identity;
import com.demo.auth.provider.OAuthProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Sign in with Discord. {@code /users/@me} carries the address under the {@code email} scope and says in
 * {@code verified} whether Discord has confirmed it; the display name falls back to the username.
 */
@Component
@ConfigurationProperties("oauth.discord")
public class DiscordProvider extends OAuthProvider {

    static final String ME_URI = "https://discord.com/api/users/@me";

    public DiscordProvider() {
        super("discord", "Discord",
                "https://discord.com/oauth2/authorize",
                "https://discord.com/api/oauth2/token",
                "identify email");
    }

    @Override
    public Identity identity(RestClient http, String accessToken) {
        return identity(get(http, ME_URI, accessToken, Map.class));
    }

    static Identity identity(Map<?, ?> me) {
        String email = string(me, "email");
        if (email.isBlank() || !flag(me, "verified")) {
            throw new IllegalStateException("your Discord email address is not verified");
        }
        String name = string(me, "global_name");
        return new Identity(email, name.isBlank() ? string(me, "username") : name);
    }
}
