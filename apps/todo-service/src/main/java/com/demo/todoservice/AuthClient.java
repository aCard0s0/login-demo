package com.demo.todoservice;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** Resolves a bearer token to its owner by asking auth-service. */
@Component
public class AuthClient {

    private final RestClient auth;

    public AuthClient(@Value("${auth.url}") String authUrl) {
        this.auth = RestClient.create(authUrl);
    }

    /** The email behind the Authorization header, or 401 if it is not a live session. */
    public String ownerOf(String authorization) {
        String token = authorization == null ? "" : authorization.replaceFirst("(?i)^Bearer ", "");
        try {
            Map<?, ?> body = auth.get()
                    .uri(b -> b.path("/api/verify").queryParam("token", token).build())
                    .retrieve()
                    .body(Map.class);
            return (String) body.get("email");
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid token");
        }
    }
}
