package com.demo.auth.provider.google;

import com.demo.auth.provider.Identity;
import com.demo.auth.provider.OAuthProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/** Sign in with Google: OpenID Connect userinfo, which states outright whether the email is verified. */
@Component
@ConfigurationProperties("oauth.google")
public class GoogleProvider extends OAuthProvider {

    static final String USERINFO_URI = "https://openidconnect.googleapis.com/v1/userinfo";

    public GoogleProvider() {
        super("google", "Google",
                "https://accounts.google.com/o/oauth2/v2/auth",
                "https://oauth2.googleapis.com/token",
                "openid email profile");
    }

    @Override
    public Identity identity(RestClient http, String accessToken) {
        return identity(get(http, USERINFO_URI, accessToken, Map.class));
    }

    /** Google says so explicitly, and can answer with either the boolean or its string form. */
    static Identity identity(Map<?, ?> me) {
        if (!Boolean.parseBoolean(string(me, "email_verified"))) {
            throw new IllegalStateException("your Google email address is not verified");
        }
        return new Identity(string(me, "email"), string(me, "name"));
    }
}
