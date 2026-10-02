package com.demo.agentservice.token;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/**
 * Asks auth-service for the long-lived token an external agent connects with. Only auth-service holds the
 * signing key, so the token is minted there and merely passed through here -- after {@code AgentService} has
 * checked that the caller owns the agent it is for.
 */
@Component
public class AgentTokens {

    private final RestClient http;

    private final String uri;

    private final String secret;

    public AgentTokens(@Value("${auth.agent-tokens-uri}") String uri, @Value("${auth.internal-secret}") String secret) {
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        requests.setReadTimeout(Duration.ofSeconds(5));
        this.http = RestClient.builder().requestFactory(requests).build();
        this.uri = uri;
        this.secret = secret;
    }

    /** A fresh token naming the owner and this one agent. Never stored here: the owner sees it once. */
    public String issue(String accountId, Long agentId) {
        try {
            // The shared secret is what tells auth-service this is agent-service asking and not just anything on the network.
            Map<?, ?> body = http.post().uri(uri).header("X-Internal-Secret", secret).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("accountId", Long.valueOf(accountId), "agentId", agentId))
                    .retrieve().body(Map.class);
            if (body == null || !(body.get("token") instanceof String token)) {
                throw new IllegalStateException("no token in the reply");
            }
            return token;
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "auth-service did not issue a token: " + e.getMessage());
        }
    }
}
