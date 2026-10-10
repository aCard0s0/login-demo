package com.demo.auth.provider;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The authorization-code flow with PKCE, by hand: build the consent URL, trade the code for an access token,
 * and read the identity behind it. Which providers exist is whatever {@link OAuthProvider} beans are on the
 * classpath; which are offered is whichever of them a deployment configured.
 *
 * <p>Hand-rolled rather than spring-boot-starter-oauth2-client because that starter brings the security
 * filter chain the services deliberately do not have -- installing it and then switching it back off is
 * more code than the flow itself. Nothing here needs a filter: the browser arrives at two ordinary endpoints,
 * which stay in the service because what happens after sign-in is the service's business.
 */
@Service
public class OAuthFlow {

    /** Bounded, so a provider that stops answering costs one login attempt and not a request thread for good. */
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final RestClient http = RestClient.builder().requestFactory(requestFactory()).build();

    private final OAuthProperties config;

    private final List<OAuthProvider> providers;

    public OAuthFlow(OAuthProperties config, List<OAuthProvider> providers) {
        this.config = config;
        this.providers = providers;
    }

    /** The providers a deployment has actually configured. What the login page renders. */
    public List<OAuthProvider> enabled() {
        return providers.stream().filter(OAuthProvider::usable).toList();
    }

    /** The configured provider a path segment names, or empty. Empty rather than an exception: the segment is user input. */
    public Optional<OAuthProvider> enabled(String key) {
        String wanted = key == null ? "" : key.strip();
        return enabled().stream().filter(provider -> provider.getKey().equalsIgnoreCase(wanted)).findFirst();
    }

    /**
     * Where to send the browser for consent. The state is echoed back and checked at the callback; the
     * verifier's hash goes out here so the provider can demand the verifier itself at the token exchange.
     */
    public String consentUri(OAuthProvider provider, String state, String verifier) {
        return provider.getAuthorizeUri()
                + "?response_type=code"
                + "&client_id=" + encode(provider.getClientId())
                + "&redirect_uri=" + encode(config.redirectUri(provider))
                + "&scope=" + encode(provider.getScope())
                + "&state=" + encode(state)
                + "&code_challenge=" + challenge(verifier)
                + "&code_challenge_method=S256"
                + (provider.formPost() ? "&response_mode=form_post" : "");
    }

    /** Everything behind the code: the provider's tokens, and the verified identity they name. */
    public Identity identity(OAuthProvider provider, String code, String verifier) {
        return provider.identity(http, tokens(provider, code, verifier));
    }

    /**
     * Trades the one-time code for the provider's tokens. The client secret travels in this back-channel POST
     * and never through the browser, which is the whole point of the code flow over the old implicit one.
     */
    private Map<?, ?> tokens(OAuthProvider provider, String code, String verifier) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("code_verifier", verifier);
        form.add("client_id", provider.getClientId());
        form.add("redirect_uri", config.redirectUri(provider));
        // The secret goes in exactly one place: RFC 6749 forbids both, and X refuses a form field.
        if (!provider.basicClientAuth()) {
            form.add("client_secret", provider.getClientSecret());
        }

        // GitHub answers form-encoded unless asked for JSON; Google always answers JSON.
        Map<?, ?> body = http.post().uri(provider.getTokenUri())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> {
                    if (provider.basicClientAuth()) {
                        headers.setBasicAuth(provider.getClientId(), provider.getClientSecret());
                    }
                })
                .body(form)
                .retrieve()
                .body(Map.class);

        Object token = body == null ? null : body.get("access_token");
        if (token == null || String.valueOf(token).isBlank()) {
            throw new IllegalStateException(provider.getKey() + " did not return an access token");
        }
        return body;
    }

    /** base64url(sha256(verifier)), the S256 method. Already URL-safe, so it goes into the query as is. */
    static String challenge(String verifier) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory in every JDK", e);
        }
    }

    private static JdkClientHttpRequestFactory requestFactory() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
        factory.setReadTimeout(TIMEOUT);
        return factory;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
