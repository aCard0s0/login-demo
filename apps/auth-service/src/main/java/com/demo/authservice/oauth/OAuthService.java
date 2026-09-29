package com.demo.authservice.oauth;

import com.demo.authservice.account.Account;
import com.demo.authservice.account.AccountService;
import com.demo.authservice.token.Tokens;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The authorization-code flow, by hand: build the consent URL, trade the code for an access token, read the
 * identity behind it, and hand back one of our own tokens.
 *
 * <p>Hand-rolled rather than spring-boot-starter-oauth2-client because that starter brings the security
 * filter chain this service deliberately does not have -- we would install it and then spend more code
 * switching it back off than the flow itself takes. Nothing here needs a filter: the browser arrives at two
 * ordinary endpoints.
 *
 * <p>An account is matched to a provider identity by <b>verified</b> email and nothing else. That is what
 * makes "log in with Google" and "log in with a password" the same account, and why an unverified address
 * is refused: accepting one would let anyone who can claim an address at a provider walk into the account
 * that already owns it here.
 */
@Service
public class OAuthService {

    private final RestClient http = RestClient.create();

    private final OAuthProperties config;

    private final AccountService accounts;

    private final Tokens tokens;

    public OAuthService(OAuthProperties config, AccountService accounts, Tokens tokens) {
        this.config = config;
        this.accounts = accounts;
        this.tokens = tokens;
    }

    /** A verified email and a display name, which is all we take from a provider. */
    record Identity(String email, String name) {}

    /** The providers a deployment has actually configured, in enum order. What the login page renders. */
    public List<OAuthProvider> enabled() {
        return Arrays.stream(OAuthProvider.values()).filter(this::isEnabled).toList();
    }

    public boolean isEnabled(OAuthProvider provider) {
        return config.of(provider).usable();
    }

    /** Where to send the browser for consent. The state is echoed back to us and checked at the callback. */
    public String consentUri(OAuthProvider provider, String state) {
        return provider.authorizeUri()
                + "?response_type=code"
                + "&client_id=" + encode(config.of(provider).getClientId())
                + "&redirect_uri=" + encode(config.redirectUri(provider))
                + "&scope=" + encode(provider.scope())
                + "&state=" + encode(state);
    }

    /**
     * Everything behind the code: the provider's token, the identity it names, the account that identity
     * belongs to, and one of our JWTs for it. Returns the token and the account's name, which is all the
     * callback redirect carries.
     */
    public Account login(OAuthProvider provider, String code) {
        Identity identity = identityOf(provider, accessToken(provider, code));
        return accounts.findOrCreateFromOAuth(identity.email(), identity.name());
    }

    public String issue(Account account) {
        return tokens.issue(account.getId(), account.getEmail(), account.getName(), account.getRole().name());
    }

    /**
     * Trades the one-time code for an access token. The client secret travels in this back-channel POST and
     * never through the browser, which is the whole point of the code flow over the old implicit one.
     */
    private String accessToken(OAuthProvider provider, String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("client_id", config.of(provider).getClientId());
        form.add("client_secret", config.of(provider).getClientSecret());
        form.add("redirect_uri", config.redirectUri(provider));

        // GitHub answers form-encoded unless asked for JSON; Google always answers JSON.
        Map<?, ?> body = http.post().uri(provider.tokenUri())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .body(form)
                .retrieve()
                .body(Map.class);

        String token = string(body, "access_token");
        if (token.isBlank()) {
            throw new IllegalStateException(provider.key() + " did not return an access token");
        }
        return token;
    }

    private Identity identityOf(OAuthProvider provider, String accessToken) {
        Map<?, ?> me = get(provider.userinfoUri(), accessToken, Map.class);
        return switch (provider) {
            case GOOGLE -> {
                // Google says so explicitly, and can answer with either the boolean or its string form.
                if (!Boolean.parseBoolean(String.valueOf(me.get("email_verified")))) {
                    throw new IllegalStateException("your Google email address is not verified");
                }
                yield new Identity(string(me, "email"), string(me, "name"));
            }
            // GitHub's /user hides the email whenever the account keeps it private, and says nothing about
            // whether it is verified either way, so the address always comes from the dedicated endpoint.
            case GITHUB -> new Identity(githubEmail(accessToken), displayName(me));
        };
    }

    private String githubEmail(String accessToken) {
        List<?> emails = get(OAuthProvider.GITHUB.userinfoUri() + "/emails", accessToken, List.class);
        return emails.stream()
                .filter(Map.class::isInstance).map(Map.class::cast)
                .filter(entry -> Boolean.TRUE.equals(entry.get("primary")) && Boolean.TRUE.equals(entry.get("verified")))
                .map(entry -> string(entry, "email"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("your GitHub account has no verified primary email"));
    }

    /** GitHub's full name is optional; the login handle is always there and is what the profile page shows. */
    private static String displayName(Map<?, ?> me) {
        String name = string(me, "name");
        return name.isBlank() ? string(me, "login") : name;
    }

    private <T> T get(String uri, String accessToken, Class<T> type) {
        return http.get().uri(uri)
                .header("Authorization", "Bearer " + accessToken)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(type);
    }

    private static String string(Map<?, ?> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
