package com.demo.auth.provider;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The back channel, over real HTTP: a throwaway server stands in for the provider's token endpoint and API,
 * so the form, the headers and the JSON decoding are the ones a real provider would see and send.
 */
class OAuthFlowIntegrationTests {

    /** A provider whose endpoints are the throwaway server, and whose identity is one API call. */
    static final class Local extends OAuthProvider {
        private final String api;

        Local(String base) {
            super("local", "Local", base + "/authorize", base + "/token", "email");
            this.api = base + "/me";
            setEnabled(true);
            setClientId("client-1");
            setClientSecret("secret-1");
        }

        @Override
        public Identity identity(RestClient http, String accessToken) {
            Map<?, ?> me = get(http, api, accessToken, Map.class);
            return new Identity(string(me, "email"), string(me, "name"));
        }
    }

    private HttpServer server;

    private final Map<String, String> seen = new ConcurrentHashMap<>();

    private volatile String tokenAnswer;

    private volatile int tokenStatus;

    private OAuthFlow flow;

    private Local provider;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/token", exchange -> {
            seen.put("token.form", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            seen.put("token.contentType", exchange.getRequestHeaders().getFirst("Content-Type"));
            seen.put("token.accept", exchange.getRequestHeaders().getFirst("Accept"));
            answer(exchange, tokenStatus, tokenAnswer);
        });
        server.createContext("/me", exchange -> {
            seen.put("me.authorization", exchange.getRequestHeaders().getFirst("Authorization"));
            answer(exchange, 200, "{\"email\":\"ada@example.com\",\"name\":\"Ada\"}");
        });
        server.start();

        String base = "http://localhost:" + server.getAddress().getPort();
        OAuthProperties config = new OAuthProperties();
        config.setRedirectBaseUrl("http://app.example.com");
        provider = new Local(base);
        flow = new OAuthFlow(config, List.of(provider));
        tokenStatus = 200;
        tokenAnswer = "{\"access_token\":\"at-1\",\"token_type\":\"bearer\"}";
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void theCodeAndVerifierBuyAnAccessTokenAndTheTokenBuysTheIdentity() {
        assertEquals(new Identity("ada@example.com", "Ada"), flow.identity(provider, "code-1", "verifier-1"));

        Map<String, String> form = form(seen.get("token.form"));
        assertEquals("authorization_code", form.get("grant_type"));
        assertEquals("code-1", form.get("code"));
        assertEquals("verifier-1", form.get("code_verifier"), "PKCE: the provider checks it against the challenge");
        assertEquals("client-1", form.get("client_id"));
        assertEquals("secret-1", form.get("client_secret"), "the secret travels here, never through the browser");
        assertEquals("http://app.example.com/api/oauth/local/callback", form.get("redirect_uri"),
                "must match the consent request's byte for byte, or the provider refuses the code");
        assertEquals("application/x-www-form-urlencoded", seen.get("token.contentType"));
        assertEquals("application/json", seen.get("token.accept"), "GitHub answers form-encoded unless asked for JSON");
        assertEquals("Bearer at-1", seen.get("me.authorization"));
    }

    @Test
    void anAnswerWithNoAccessTokenOrAnErrorStatusIsARefusal() {
        tokenAnswer = "{\"error\":\"bad_verification_code\"}";
        assertThrows(IllegalStateException.class, () -> flow.identity(provider, "code-1", "verifier-1"),
                "GitHub answers a spent code with 200 and an error field");

        tokenAnswer = "{\"access_token\":\"\"}";
        assertThrows(IllegalStateException.class, () -> flow.identity(provider, "code-1", "verifier-1"));

        tokenStatus = 400;
        tokenAnswer = "{\"error\":\"invalid_grant\"}";
        assertThrows(RestClientException.class, () -> flow.identity(provider, "code-1", "verifier-1"));
        assertNull(seen.get("me.authorization"), "no token, so the API is never called");
    }

    private static void answer(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static Map<String, String> form(String body) {
        return Arrays.stream(body.split("&")).map(pair -> pair.split("=", 2)).collect(Collectors.toMap(
                pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                pair -> pair.length > 1 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : ""));
    }
}
