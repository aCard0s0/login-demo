package com.demo.authservice;

import com.demo.authservice.user.entities.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fail-closed. A controller is authenticated by declaring a {@link User} parameter, which means a new endpoint
 * that forgets the parameter is public by default and nothing at runtime would say so. This sweep is what says
 * so: every mapping in the service is either on the list of the ones meant to be public, guarded by the
 * internal secret, or takes a {@code User} -- and the ones that take a {@code User} really do answer 401 to a
 * request with no token, over HTTP, not just by the look of their signature.
 *
 * <p>Adding an endpoint means either giving it a {@code User} or adding it to {@link #PUBLIC} here, on purpose.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/test-sweep.db",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.hikari.maximum-pool-size=1",
        "auth.internal-secret=test-internal-secret",
})
@AutoConfigureMockMvc
class EndpointAuthSweepTests {

    /** Method and path of every endpoint that is meant to answer without a token. The README's bottom rows. */
    static final Set<String> PUBLIC = Set.of(
            "POST /api/users",
            "POST /api/login",
            "GET /api/public/stats",
            "GET /api/jwks.json",
            "GET /api/oauth/providers",
            "GET /api/oauth/{provider}/start",
            "GET /api/oauth/{provider}/callback",
            "POST /api/oauth/{provider}/callback",
            // Compose network only: never proxied, and it names ids and counters, nothing else.
            "GET /internal/token-versions");

    @Autowired
    RequestMappingHandlerMapping mappings;

    @Autowired
    MockMvc mvc;

    @Test
    void everyEndpointIsPublicOnPurposeOrGuardedBySecretOrTakesTheCaller() throws Exception {
        List<String> seen = new ArrayList<>();
        List<String> unguarded = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mappings.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            HandlerMethod handler = entry.getValue();
            if (!handler.getBeanType().getPackageName().startsWith("com.demo.authservice")) {
                continue; // Boot's own error endpoint
            }
            for (var method : info.getMethodsCondition().getMethods()) {
                for (String path : info.getPathPatternsCondition().getPatternValues()) {
                    String endpoint = method + " " + path;
                    seen.add(endpoint);
                    if (PUBLIC.contains(endpoint)) {
                        continue;
                    }
                    if (takesSecret(handler)) {
                        assertEquals(403, hit(method.name(), path, null), "no secret must be 403 on " + endpoint);
                        continue;
                    }
                    if (!takesCaller(handler)) {
                        unguarded.add(endpoint);
                        continue;
                    }
                    assertEquals(401, hit(method.name(), path, null), "no token must be 401 on " + endpoint);
                    assertEquals(401, hit(method.name(), path, "Bearer nonsense"), "a bad token must be 401 on " + endpoint);
                }
            }
        }
        assertTrue(unguarded.isEmpty(), "endpoints that are neither public on purpose nor authenticated: " + unguarded);
        assertTrue(seen.containsAll(PUBLIC), "the public list names endpoints that no longer exist: " + PUBLIC.stream()
                .filter(endpoint -> !seen.contains(endpoint)).toList());
    }

    @Test
    void theBearerSchemeIsCaseInsensitiveAndTheRawTokenIsNotAccepted() throws Exception {
        assertEquals(401, hit("GET", "/api/users/me", "bearer nonsense"), "a lowercase scheme is still a scheme, and the token is still bad");
        mvc.perform(request("GET", java.net.URI.create("/api/users/me")).header("Authorization", "Basic dXNlcjpwYXNz"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid or expired token"));
    }

    private int hit(String method, String path, String authorization) throws Exception {
        var request = request(method, java.net.URI.create(path.replace("{id}", "1").replace("{agentId}", "1")))
                .contentType(MediaType.APPLICATION_JSON).content("{}");
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    private static boolean takesCaller(HandlerMethod handler) {
        return Arrays.stream(handler.getMethodParameters()).anyMatch(p -> p.getParameterType() == User.class);
    }

    private static boolean takesSecret(HandlerMethod handler) {
        return Arrays.stream(handler.getMethodParameters()).anyMatch(p -> {
            RequestHeader header = p.getParameterAnnotation(RequestHeader.class);
            return header != null && "X-Internal-Secret".equals(header.value());
        });
    }
}
