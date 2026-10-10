package com.demo.auth.provider;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Where the browser comes back to. Per-provider credentials are not here: each provider binds its own
 * {@code oauth.<key>.*}, so adding one is adding a module, not editing this class.
 */
@Component
@ConfigurationProperties("oauth")
@Getter
@Setter
public class OAuthProperties {

    /**
     * The public address the browser uses, and so the origin the provider must be told to redirect to. The
     * frontend's address rather than the service's own when the browser only ever reaches it through a proxy.
     */
    private String redirectBaseUrl = "http://localhost:3000";

    /** The callback route, under the base. {@code {provider}} is replaced by the provider's key. */
    private String callbackPath = "/api/oauth/{provider}/callback";

    /** The base with no trailing slash, for building the routes under it. */
    public String baseUrl() {
        return redirectBaseUrl.replaceAll("/+$", "");
    }

    /** Where the provider sends the browser back to. Must match the callback URL registered with them. */
    public String redirectUri(OAuthProvider provider) {
        return baseUrl() + callbackPath.replace("{provider}", provider.getKey());
    }
}
