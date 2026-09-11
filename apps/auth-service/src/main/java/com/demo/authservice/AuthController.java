package com.demo.authservice;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    public record NewAccount(String name, String email, String password) {}

    public record AccountResponse(Long id, String name, String email) {}

    public record LoginRequest(String email, String password) {}

    public record LoginResponse(String token, String name, String email) {}

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse register(@RequestBody NewAccount req) {
        Account saved = auth.register(req.name(), req.email(), req.password());
        return new AccountResponse(saved.getId(), saved.getName(), saved.getEmail());
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {
        return auth.login(req.email(), req.password())
                .<ResponseEntity<?>>map(s -> ResponseEntity.ok(
                        new LoginResponse(s.token(), s.account().getName(), s.account().getEmail())))
                .orElseGet(() -> ResponseEntity.status(401).body(Map.of("error", "invalid credentials")));
    }

    /** Called by other services to resolve a token to its owner. 200 + account, or 401. */
    @GetMapping("/verify")
    public ResponseEntity<?> verify(@RequestParam String token) {
        return auth.verify(token)
                .<ResponseEntity<?>>map(a -> ResponseEntity.ok(Map.of("email", a.getEmail(), "name", a.getName())))
                .orElseGet(() -> ResponseEntity.status(401).build());
    }

    @PostMapping("/logout")
    public void logout(@RequestParam String token) {
        auth.logout(token);
    }

    /** Turns the service's rejections into the {error} shape the frontend already renders. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
