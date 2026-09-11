package com.demo.authservice.token;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Deliberately unauthenticated: other services read the key without holding a token of their own. */
@RestController
public class JwksController {

    private final Tokens tokens;

    public JwksController(Tokens tokens) {
        this.tokens = tokens;
    }

    /** The public half of the signing key, so todo-service can verify tokens without ever holding the private half. */
    @GetMapping("/api/jwks.json")
    public Map<String, Object> jwks() {
        return tokens.publicJwks();
    }
}
