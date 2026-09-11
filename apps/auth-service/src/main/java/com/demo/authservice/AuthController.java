package com.demo.authservice;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api")
public class AuthController {

    // ponytail: demo credentials and in-memory sessions. Swap for a user store + JWT when this stops being a demo.
    private static final Map<String, String> USERS = Map.of("demo", "demo", "alice", "wonderland");

    private final Map<String, String> sessions = new ConcurrentHashMap<>();

    public record LoginRequest(String username, String password) {}

    public record LoginResponse(String token, String username) {}

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {
        if (req.username() == null || !req.password().equals(USERS.get(req.username()))) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid credentials"));
        }
        String token = UUID.randomUUID().toString();
        sessions.put(token, req.username());
        return ResponseEntity.ok(new LoginResponse(token, req.username()));
    }

    /** Called by other services to resolve a token to a username. 200 + username, or 401. */
    @GetMapping("/verify")
    public ResponseEntity<?> verify(@RequestParam String token) {
        String username = sessions.get(token);
        return username == null
                ? ResponseEntity.status(401).build()
                : ResponseEntity.ok(Map.of("username", username));
    }

    @PostMapping("/logout")
    public void logout(@RequestParam String token) {
        sessions.remove(token);
    }
}
