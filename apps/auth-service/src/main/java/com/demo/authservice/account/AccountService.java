package com.demo.authservice.account;

import com.demo.authservice.token.Tokens;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** The account itself: who exists, what their details are, and which account a caller's token names. */
@Service
public class AccountService {

    /** Minimum we are willing to hash. Short passwords are the one input rule worth enforcing here. */
    private static final int MIN_PASSWORD_LENGTH = 8;

    /**
     * BCrypt reads 72 bytes and silently ignores the rest, which would make any two passwords sharing a
     * 72-byte prefix the same password. Reject the long ones instead of truncating them behind the user's back.
     */
    private static final int MAX_PASSWORD_BYTES = 72;

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    private final AccountRepository accounts;

    private final Tokens tokens;

    public AccountService(AccountRepository accounts, Tokens tokens) {
        this.accounts = accounts;
        this.tokens = tokens;
    }

    @Transactional
    public Account register(String name, String email, String password) {
        String cleanName = cleanName(name);
        String cleanEmail = cleanEmail(email);
        checkPassword(password);
        // Racing registrations both get past this; the unique index on accounts.email is what actually decides.
        if (accounts.existsByEmail(cleanEmail)) {
            throw new IllegalArgumentException("that email is already registered");
        }
        return accounts.save(new Account(cleanName, cleanEmail, encoder.encode(password)));
    }

    /**
     * Changes the caller's own details. The current password is always required, even to change only the name,
     * so a borrowed tab cannot quietly take an account over. A blank newPassword leaves the password alone.
     */
    @Transactional
    public Account update(Long accountId, String name, String email, String currentPassword, String newPassword) {
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("account not found"));
        if (currentPassword == null || !encoder.matches(currentPassword, account.getPasswordHash())) {
            throw new IllegalArgumentException("current password is wrong");
        }
        String cleanName = cleanName(name);
        String cleanEmail = cleanEmail(email);
        if (!cleanEmail.equals(account.getEmail()) && accounts.existsByEmail(cleanEmail)) {
            throw new IllegalArgumentException("that email is already registered");
        }
        account.setName(cleanName);
        account.setEmail(cleanEmail);
        if (newPassword != null && !newPassword.isBlank()) {
            checkPassword(newPassword);
            account.setPasswordHash(encoder.encode(newPassword));
        }
        return accounts.save(account);
    }

    /**
     * Resolves a token to the account it names, or empty if it does not check out. The account is re-read rather
     * than taken from the token's claims, so a rename shows up straight away instead of at the next login.
     */
    public Optional<Account> byToken(String token) {
        return tokens.accountIdFrom(token).flatMap(accounts::findById);
    }

    /** How many accounts exist. Public: a count gives away nothing about who they are. */
    public long count() {
        return accounts.count();
    }

    private static String cleanName(String name) {
        String clean = name == null ? "" : name.strip();
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("name is required");
        }
        return clean;
    }

    private static String cleanEmail(String email) {
        String clean = email == null ? "" : email.strip().toLowerCase();
        if (!clean.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw new IllegalArgumentException("a valid email is required");
        }
        return clean;
    }

    private static void checkPassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new IllegalArgumentException("password must be at most " + MAX_PASSWORD_BYTES + " bytes");
        }
    }
}
