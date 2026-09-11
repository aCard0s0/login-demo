package com.demo.authservice.account;

import com.demo.authservice.token.Tokens;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
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

    /** Every account. Only ever reached by a role that {@link Role#readsEveryone()}; the controller checks that. */
    public List<Account> all() {
        return accounts.findAll();
    }

    /**
     * Moves an account to another role. The caller is passed in so the one account that could lock everybody
     * out -- an admin demoting itself, leaving nobody able to promote anyone again -- is refused here rather
     * than relying on whoever writes the next controller to remember.
     */
    @Transactional
    public Account changeRole(Account admin, Long accountId, Role role) {
        if (role == null) {
            throw new IllegalArgumentException("a role is required");
        }
        if (admin.getId().equals(accountId) && role != Role.ADMIN) {
            throw new IllegalArgumentException("an admin cannot take its own admin rights away");
        }
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("account not found"));
        account.setRole(role);
        return accounts.save(account);
    }

    /**
     * Makes sure the deployment's admin exists, at startup, from the credentials in the environment.
     *
     * <p>Create-only on purpose. If the address is already registered it is promoted but its password is left
     * exactly as it is, so a restart can never quietly reset a password the admin has since changed, and a
     * stale value left in .env cannot hand the account back to whoever last read that file.
     */
    @Transactional
    public Account ensureAdmin(String email, String password) {
        String cleanEmail = cleanEmail(email);
        Optional<Account> existing = accounts.findByEmail(cleanEmail);
        if (existing.isPresent()) {
            Account admin = existing.get();
            if (admin.getRole() != Role.ADMIN) {
                admin.setRole(Role.ADMIN);
                return accounts.save(admin);
            }
            return admin;
        }
        checkPassword(password);
        Account admin = new Account("Admin", cleanEmail, encoder.encode(password));
        admin.setRole(Role.ADMIN);
        return accounts.save(admin);
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
