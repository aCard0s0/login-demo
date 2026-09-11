package com.demo.authservice;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthService {

    /** Minimum we are willing to hash. Short passwords are the one input rule worth enforcing here. */
    private static final int MIN_PASSWORD_LENGTH = 8;

    /** Compared against when the email is unknown, so a missing account costs the same time as a wrong password. */
    private static final String DUMMY_HASH = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    // ponytail: sessions are in-memory, so a restart logs everyone out. Move to a table or JWT if that matters.
    private final Map<String, Long> sessions = new ConcurrentHashMap<>();

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    private final AccountRepository accounts;

    public AuthService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    /** A live login: the bearer token plus the account it belongs to. */
    public record Session(String token, Account account) {}

    @Transactional
    public Account register(String name, String email, String password) {
        String cleanName = name == null ? "" : name.strip();
        String cleanEmail = email == null ? "" : email.strip().toLowerCase();
        if (cleanName.isEmpty()) {
            throw new IllegalArgumentException("name is required");
        }
        if (!cleanEmail.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw new IllegalArgumentException("a valid email is required");
        }
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        if (accounts.existsByEmail(cleanEmail)) {
            throw new IllegalArgumentException("that email is already registered");
        }
        return accounts.save(new Account(cleanName, cleanEmail, encoder.encode(password)));
    }

    public Optional<Session> login(String email, String password) {
        String cleanEmail = email == null ? "" : email.strip().toLowerCase();
        Optional<Account> account = accounts.findByEmail(cleanEmail);
        if (account.isEmpty()) {
            encoder.matches(password == null ? "" : password, DUMMY_HASH);
            return Optional.empty();
        }
        if (password == null || !encoder.matches(password, account.get().getPasswordHash())) {
            return Optional.empty();
        }
        String token = UUID.randomUUID().toString();
        sessions.put(token, account.get().getId());
        return Optional.of(new Session(token, account.get()));
    }

    /** Resolves a bearer token to its account, or empty if the token is not a live session. */
    public Optional<Account> verify(String token) {
        Long accountId = token == null ? null : sessions.get(token);
        return accountId == null ? Optional.empty() : accounts.findById(accountId);
    }

    public void logout(String token) {
        sessions.remove(token);
    }
}
