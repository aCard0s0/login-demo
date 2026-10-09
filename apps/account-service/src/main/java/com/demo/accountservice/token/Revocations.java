package com.demo.accountservice.token;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Which tokens auth-service has revoked, so a suspended or signed-out account is turned away here too and not
 * only at auth-service. Tokens are checked in process, so without this a revoked one would keep working
 * against the accounts until it expired.
 *
 * <p>auth-service publishes, per account it has ever revoked, the token version a token must carry to count.
 * The list is cached and re-read at most every few seconds, so a revocation bites here within that window
 * rather than on the next request.
 */
// ponytail: fails open. If auth-service cannot be reached the last list stands, so a revocation made while it
// is down is not seen here until it is back. Failing closed would take the accounts down with auth-service.
@Component
public class Revocations {

    private static final Logger log = LoggerFactory.getLogger(Revocations.class);

    static final Duration MAX_AGE = Duration.ofSeconds(10);

    private final RestClient http;

    private final String uri;

    private volatile Map<String, Integer> versions = Map.of();

    private volatile Instant fetchedAt = Instant.MIN;

    public Revocations(@Value("${auth.token-versions-uri}") String uri) {
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        // Short, because the refresh happens on a caller's request and that caller is waiting for it.
        requests.setReadTimeout(Duration.ofSeconds(2));
        this.http = RestClient.builder().requestFactory(requests).build();
        this.uri = uri;
    }

    /** The lowest token version this account's tokens must carry. Zero for an account never revoked. */
    public int minimumVersion(String accountId) {
        if (stale()) {
            refresh();
        }
        return versions.getOrDefault(accountId, 0);
    }

    private boolean stale() {
        return fetchedAt.plus(MAX_AGE).isBefore(Instant.now());
    }

    private synchronized void refresh() {
        if (!stale()) {
            return; // another request refreshed it while this one waited
        }
        // Stamped before the call, so a dead auth-service is retried once a window and not on every request.
        fetchedAt = Instant.now();
        try {
            Map<String, Integer> fetched = http.get().uri(uri).retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Integer>>() {});
            if (fetched != null) {
                versions = Map.copyOf(fetched);
            }
        } catch (Exception e) {
            log.warn("could not refresh revoked tokens from {}, keeping the last list: {}", uri, e.getMessage());
        }
    }
}
