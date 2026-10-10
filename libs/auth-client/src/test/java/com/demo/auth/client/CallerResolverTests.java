package com.demo.auth.client;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The resolver inside Spring MVC, as a service's controller meets it: a real JWKS and feed behind it, and a
 * controller that does nothing but take a {@link Caller}. What a controller never has to check is the point.
 */
class CallerResolverTests {

    @RestController
    static class Whoami {
        @GetMapping("/whoami")
        String whoami(Caller caller) {
            return caller.role() + " " + caller.userId();
        }
    }

    private HttpServer auth;

    private RSAKey key;

    private MockMvc mvc;

    @BeforeEach
    void start() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("k").generate();
        auth = JwtVerifierTests.publish(Map.of(
                "/jwks.json", new JWKSet(key.toPublicJWK()).toString(),
                "/token-versions", "{\"7\":1}"));
        String base = "http://localhost:" + auth.getAddress().getPort();
        CallerResolver resolver = new CallerResolver(new JwtVerifier(base + "/jwks.json", new Revocations(base + "/token-versions")));
        mvc = MockMvcBuilders.standaloneSetup(new Whoami()).setCustomArgumentResolvers(resolver).build();
    }

    @AfterEach
    void stop() {
        auth.stop(0);
    }

    @Test
    void aUserTokenReachesTheControllerAndNothingElseDoes() throws Exception {
        Instant later = Instant.now().plusSeconds(300);

        mvc.perform(get("/whoami").header("Authorization", "Bearer " + JwtVerifierTests.token(key, "42", later, "MODERATOR")))
                .andExpect(status().isOk())
                .andExpect(content().string("MODERATOR 42"));

        mvc.perform(get("/whoami")).andExpect(status().isUnauthorized());
        mvc.perform(get("/whoami").header("Authorization", "Bearer junk")).andExpect(status().isUnauthorized());
        mvc.perform(get("/whoami").header("Authorization", "Bearer " + JwtVerifierTests.token(key, "7", later, "ADMIN", 0)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/whoami").header("Authorization", "Bearer " + JwtVerifierTests.token(key, "42", later, "AGENT", null, 9L, 0)))
                .andExpect(status().isForbidden());
    }
}
