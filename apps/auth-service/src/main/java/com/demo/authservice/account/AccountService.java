package com.demo.authservice.account;

import com.demo.authservice.support.AttemptWindow;
import com.demo.authservice.support.TooManyAttemptsException;
import com.demo.authservice.token.Tokens;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

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

    /** The width Hibernate gives a String column. Checked here so Postgres does not answer with a misleading 400. */
    private static final int MAX_COLUMN_LENGTH = 255;

    // ponytail: one service-wide cap, not per client. Every request arrives from the Node proxy under one
    // address and the proxy does not set X-Forwarded-For, so per-address would be this anyway; a run of bots
    // fills the window and honest registrations wait it out with them. Per client needs the proxy to set the
    // header and this side to trust it.
    private final AttemptWindow registrations = new AttemptWindow(30, Duration.ofMinutes(15));

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    private final AccountRepository accounts;

    private final Tokens tokens;

    public AccountService(AccountRepository accounts, Tokens tokens) {
        this.accounts = accounts;
        this.tokens = tokens;
    }

    @Transactional
    public Account register(String name, String email, String password) {
        if (registrations.exceeded("")) {
            throw new TooManyAttemptsException("too many registrations right now, try again in "
                    + registrations.window().toMinutes() + " minutes");
        }
        // Counted before the checks, so a rejected body costs an attempt the same as an accepted one.
        registrations.record("");
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
     * The account behind a provider identity: the one already registered with that email, or a new one.
     *
     * <p>Matching on the email is what makes signing in with Google and signing in with a password the same
     * account rather than two. The caller is responsible for having checked that the provider verified the
     * address -- this method cannot tell, and an unverified one would be a way to walk into somebody else's
     * account.
     */
    // ponytail: no provider/provider-id columns, so an account created this way has no password it can be
    // asked for, and /account's "current password" gate locks its owner out of editing. A "set a password"
    // flow is the upgrade; linking by verified email is the whole requirement today.
    @Transactional
    public Account findOrCreateFromOAuth(String email, String name) {
        String cleanEmail = cleanEmail(email);
        return accounts.findByEmail(cleanEmail).orElseGet(() -> {
            // A hash of a value nobody holds, rather than a nullable column every password path would then
            // have to test for. No password can match it, so the only way in stays the provider.
            String unusable = encoder.encode(UUID.randomUUID().toString());
            return accounts.save(new Account(cleanName(nameOr(name, cleanEmail)), cleanEmail, unusable));
        });
    }

    /**
     * Resolves a token to the account it names, or empty if it does not check out. The account is re-read rather
     * than taken from the token's claims, so a rename shows up straight away instead of at the next login, and
     * a suspension or a revocation bites on the very next request.
     */
    public Optional<Account> byToken(String token) {
        return tokens.claimsFrom(token).flatMap(claims -> accounts.findById(claims.accountId())
                .filter(account -> !account.isSuspended() && account.getTokenVersion() == claims.version()));
    }

    /** A token for the account, stamped with its current version so a later revocation can kill it. */
    public String issue(Account account) {
        return tokens.issue(account.getId(), account.getEmail(), account.getName(), account.getRole().name(),
                account.getTokenVersion());
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
     * Suspends or reactivates an account. Suspending also revokes, so the tokens it holds die with it rather
     * than at their expiry. An admin cannot suspend itself, for the same reason it cannot demote itself.
     */
    @Transactional
    public Account setSuspended(Account admin, Long accountId, boolean suspended) {
        if (admin.getId().equals(accountId) && suspended) {
            throw new IllegalArgumentException("an admin cannot suspend itself");
        }
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("account not found"));
        if (suspended && !account.isSuspended()) {
            account.setTokenVersion(account.getTokenVersion() + 1);
        }
        account.setSuspended(suspended);
        return accounts.save(account);
    }

    /** Signs an account out everywhere: every token it holds stops working. It can log straight back in. */
    @Transactional
    public Account revokeTokens(Long accountId) {
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("account not found"));
        account.setTokenVersion(account.getTokenVersion() + 1);
        return accounts.save(account);
    }

    /** Every account that has had its tokens revoked, by id, with the version a token must carry to count. */
    public Map<Long, Integer> tokenVersions() {
        return accounts.findByTokenVersionGreaterThan(0).stream()
                .collect(Collectors.toMap(Account::getId, Account::getTokenVersion));
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

    /** Providers do not all have a name to give; the address's local part is a better fallback than a refusal. */
    private static String nameOr(String name, String email) {
        return name == null || name.isBlank() ? email.substring(0, email.indexOf('@')) : name;
    }

    private static String cleanName(String name) {
        String clean = name == null ? "" : name.strip();
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("name is required");
        }
        if (clean.length() > MAX_COLUMN_LENGTH) {
            throw new IllegalArgumentException("name must be at most " + MAX_COLUMN_LENGTH + " characters");
        }
        return clean;
    }

    private static String cleanEmail(String email) {
        String clean = email == null ? "" : email.strip().toLowerCase();
        if (clean.length() > MAX_COLUMN_LENGTH || !clean.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
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
