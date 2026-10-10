package com.demo.auth.provider.linkedin;

import com.demo.auth.provider.Identity;
import com.demo.auth.provider.OAuthProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Sign in with LinkedIn, through its OpenID Connect product ("Sign In with LinkedIn using OpenID Connect",
 * which the app must have added). The userinfo endpoint states {@code email_verified} outright.
 */
@Component
@ConfigurationProperties("oauth.linkedin")
public class LinkedInProvider extends OAuthProvider {

    static final String USERINFO_URI = "https://api.linkedin.com/v2/userinfo";

    public LinkedInProvider() {
        super("linkedin", "LinkedIn",
                "https://www.linkedin.com/oauth/v2/authorization",
                "https://www.linkedin.com/oauth/v2/accessToken",
                "openid profile email");
    }

    @Override
    public Identity identity(RestClient http, String accessToken) {
        return identity(get(http, USERINFO_URI, accessToken, Map.class));
    }

    static Identity identity(Map<?, ?> me) {
        String email = string(me, "email");
        if (email.isBlank() || !flag(me, "email_verified")) {
            throw new IllegalStateException("your LinkedIn email address is not verified");
        }
        return new Identity(email, string(me, "name"));
    }
}
