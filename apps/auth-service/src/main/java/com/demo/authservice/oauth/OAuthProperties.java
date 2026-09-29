package com.demo.authservice.oauth;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Per-provider switch and credentials, from the environment.
 *
 * <p>Named fields rather than a {@code Map<String, Client>} so {@code OAUTH_GOOGLE_CLIENT_ID} binds by
 * Spring's relaxed rules the same way {@code ADMIN_EMAIL} already does; map keys do not survive that trip.
 */
@Component
@ConfigurationProperties("oauth")
@Getter
@Setter
public class OAuthProperties {

    /**
     * Where the browser comes back to, and so the origin the provider must be told to redirect to. The
     * frontend's public address, not this service's: the browser only ever reaches us through the proxy.
     */
    private String redirectBaseUrl = "http://localhost:3000";

    private final Client google = new Client();

    private final Client github = new Client();

    @Getter
    @Setter
    public static class Client {

        private boolean enabled;

        private String clientId;

        private String clientSecret;

        /**
         * Whether this provider can actually be offered. Enabled but unconfigured counts as off: a button
         * that leads to a provider error page is worse than no button, and half-set credentials are the
         * normal state of a .env somebody copied and did not finish.
         */
        public boolean usable() {
            return enabled && notBlank(clientId) && notBlank(clientSecret);
        }

        private static boolean notBlank(String value) {
            return value != null && !value.isBlank();
        }
    }

    public Client of(OAuthProvider provider) {
        return provider == OAuthProvider.GOOGLE ? google : github;
    }

    /** Where the provider sends the browser back to. Must match the callback URL registered with them. */
    public String redirectUri(OAuthProvider provider) {
        return redirectBaseUrl.replaceAll("/+$", "") + "/api/oauth/" + provider.key() + "/callback";
    }
}
