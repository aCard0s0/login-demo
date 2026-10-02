package com.demo.authservice.oauth;

import com.demo.authservice.account.Account;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * The two redirects a browser walks through to log in with a provider, plus the list the login page reads
 * to know which buttons to draw.
 *
 * <p>Both endpoints answer with a 302 rather than JSON, because the browser -- not the frontend's fetch --
 * is what travels through them.
 */
@RestController
@RequestMapping("/api/oauth")
public class OAuthController {

    private static final Logger log = LoggerFactory.getLogger(OAuthController.class);

    /**
     * Guards against a forged callback: the state is planted in a cookie on the way out and must come back
     * in both the cookie and the query. An attacker can make a browser visit our callback, but cannot set
     * or read a cookie on this origin, so they cannot make the two halves match.
     *
     * <p>SameSite=Lax rather than Strict on purpose -- the callback is a cross-site top-level navigation
     * from the provider, and Strict would withhold the cookie exactly when it is needed.
     */
    private static final String STATE_COOKIE = "oauth_state";

    /**
     * PKCE. The verifier is planted next to the state and only ever leaves this service in the back-channel
     * token request; the provider is shown its hash on the way out. A code lifted from the redirect is then
     * worthless to anyone who does not also hold this cookie -- which is nobody but the browser that started.
     */
    private static final String VERIFIER_COOKIE = "oauth_verifier";

    /** Long enough to read a consent screen, short enough that an abandoned attempt does not linger. */
    private static final Duration STATE_TTL = Duration.ofMinutes(10);

    private final SecureRandom random = new SecureRandom();

    private final OAuthService oauth;

    private final OAuthProperties config;

    public OAuthController(OAuthService oauth, OAuthProperties config) {
        this.oauth = oauth;
        this.config = config;
    }

    /** One enabled provider, as the login page needs it. */
    public record ProviderSummary(String key, String label) {}

    /**
     * Which providers this deployment offers. Public, and deliberately says nothing a consent screen would
     * not: the client id is already visible to anyone who clicks a button.
     */
    @GetMapping("/providers")
    public List<ProviderSummary> providers() {
        return oauth.enabled().stream()
                .map(provider -> new ProviderSummary(provider.key(), label(provider)))
                .toList();
    }

    /** Step one: plant the state and send the browser to the provider's consent screen. */
    @GetMapping("/{provider}/start")
    public ResponseEntity<Void> start(@PathVariable String provider) {
        OAuthProvider target = enabled(provider);
        String state = secret();
        String verifier = secret();
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, oauth.consentUri(target, state, verifier))
                .header(HttpHeaders.SET_COOKIE, cookie(STATE_COOKIE, state, STATE_TTL).toString())
                .header(HttpHeaders.SET_COOKIE, cookie(VERIFIER_COOKIE, verifier, STATE_TTL).toString())
                .build();
    }

    /**
     * Step two: the provider sends the browser back here. Everything that can go wrong ends the same way --
     * back at the login page with a message in the fragment -- because there is nobody to read a JSON error
     * at this point in the flow, only a browser mid-redirect.
     */
    @GetMapping("/{provider}/callback")
    public ResponseEntity<Void> callback(@PathVariable String provider,
                                         @RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error,
                                         @CookieValue(name = STATE_COOKIE, required = false) String expectedState,
                                         @CookieValue(name = VERIFIER_COOKIE, required = false) String verifier) {
        OAuthProvider target = enabled(provider);
        // Whatever happens next, this attempt's state and verifier are spent.
        ResponseEntity.BodyBuilder done = ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.SET_COOKIE, cookie(STATE_COOKIE, "", Duration.ZERO).toString())
                .header(HttpHeaders.SET_COOKIE, cookie(VERIFIER_COOKIE, "", Duration.ZERO).toString());

        if (error != null && !error.isBlank()) {
            return done.header(HttpHeaders.LOCATION, landing("error", "sign-in with " + label(target) + " was cancelled")).build();
        }
        if (!matches(expectedState, state) || verifier == null || verifier.isBlank()) {
            return done.header(HttpHeaders.LOCATION, landing("error", "that sign-in did not start here, try again")).build();
        }
        if (code == null || code.isBlank()) {
            return done.header(HttpHeaders.LOCATION, landing("error", label(target) + " sent no authorization code")).build();
        }
        try {
            Account account = oauth.login(target, code, verifier);
            if (account.isSuspended()) {
                return done.header(HttpHeaders.LOCATION, landing("error", "this account is suspended")).build();
            }
            return done.header(HttpHeaders.LOCATION, landing("token", oauth.issue(account))
                    + "&name=" + encode(account.getName()) + "&role=" + account.getRole().name()).build();
        } catch (Exception e) {
            // The provider's own wording can name internals, so the browser gets a flat message and the
            // detail goes to the container log.
            log.warn("{} sign-in failed", target.key(), e);
            return done.header(HttpHeaders.LOCATION, landing("error", "could not sign you in with " + label(target))).build();
        }
    }

    /** A disabled provider is a 404, the same answer an unknown one gets: config is nobody else's business. */
    private OAuthProvider enabled(String provider) {
        return OAuthProvider.of(provider)
                .filter(oauth::isEnabled)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown or disabled provider"));
    }

    /**
     * Back to the login page, with the result in the fragment. A fragment rather than a query string so the
     * token is never sent to a server, logged by the proxy, or handed on in a Referer header.
     */
    private String landing(String key, String value) {
        return config.getRedirectBaseUrl().replaceAll("/+$", "") + "/login#" + key + "=" + encode(value);
    }

    /** 32 random bytes as base64url: 43 characters, which is exactly PKCE's minimum for a verifier. */
    private String secret() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private ResponseCookie cookie(String name, String value, Duration maxAge) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(config.getRedirectBaseUrl().startsWith("https://"))
                .sameSite("Lax")
                .path("/api/oauth")
                .maxAge(maxAge)
                .build();
    }

    /** Constant-time, so a callback cannot be used to guess a state one character at a time. */
    private static boolean matches(String expected, String actual) {
        if (expected == null || expected.isBlank() || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private static String label(OAuthProvider provider) {
        return provider == OAuthProvider.GOOGLE ? "Google" : "GitHub";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
