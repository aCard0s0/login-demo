package com.demo.auth.provider.github;

import com.demo.auth.provider.Identity;
import com.demo.auth.provider.OAuthProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Sign in with GitHub. {@code /user} hides the email whenever the account keeps it private, and says nothing
 * about whether it is verified either way, so the address always comes from {@code /user/emails}, where the
 * {@code primary} and {@code verified} flags live. The {@code user:email} scope is what makes that readable.
 */
@Component
@ConfigurationProperties("oauth.github")
public class GitHubProvider extends OAuthProvider {

    static final String USER_URI = "https://api.github.com/user";

    static final String EMAILS_URI = USER_URI + "/emails";

    public GitHubProvider() {
        super("github", "GitHub",
                "https://github.com/login/oauth/authorize",
                "https://github.com/login/oauth/access_token",
                "read:user user:email");
    }

    @Override
    public Identity identity(RestClient http, String accessToken) {
        return identity(get(http, USER_URI, accessToken, Map.class), get(http, EMAILS_URI, accessToken, List.class));
    }

    /** The verified primary address, and the full name with the login handle as the fallback GitHub always has. */
    static Identity identity(Map<?, ?> me, List<?> emails) {
        String email = emails == null ? null : emails.stream()
                .filter(Map.class::isInstance).map(Map.class::cast)
                .filter(entry -> Boolean.TRUE.equals(entry.get("primary")) && Boolean.TRUE.equals(entry.get("verified")))
                .map(entry -> string(entry, "email"))
                .findFirst()
                .orElse(null);
        if (email == null || email.isBlank()) {
            throw new IllegalStateException("your GitHub account has no verified primary email");
        }
        String name = string(me, "name");
        return new Identity(email, name.isBlank() ? string(me, "login") : name);
    }
}
