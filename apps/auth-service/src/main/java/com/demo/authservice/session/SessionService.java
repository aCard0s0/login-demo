package com.demo.authservice.session;

import com.demo.authservice.account.Account;
import com.demo.authservice.account.AccountRepository;
import com.demo.authservice.token.Tokens;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Turning a password into a token, and refusing to keep trying once an email has failed too often. */
@Service
public class SessionService {

    /** Compared against when the email is unknown, so a missing account costs the same time as a wrong password. */
    private static final String DUMMY_HASH = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    /** Failed logins one email may collect inside the window before it stops being tried at all. */
    private static final int MAX_FAILED_LOGINS = 5;

    /** How long a run of failed logins is remembered, so also how long a locked-out email stays locked. */
    private static final Duration LOCKOUT_WINDOW = Duration.ofMinutes(15);

    /** Stops the failure map from growing without bound when someone walks a list of addresses through it. */
    private static final int FAILURE_MAP_SWEEP_AT = 10_000;

    /** A run of failed logins for one email, and when that run started. */
    private record Failures(int count, Instant since) {}

    // ponytail: per email, not per IP, because every browser request arrives from the Node proxy and would share
    // one counter. The cost is that someone can lock a known address out on purpose; per-IP needs X-Forwarded-For.
    private final Map<String, Failures> failures = new ConcurrentHashMap<>();

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    private final AccountRepository accounts;

    private final Tokens tokens;

    public SessionService(AccountRepository accounts, Tokens tokens) {
        this.accounts = accounts;
        this.tokens = tokens;
    }

    public Optional<Session> login(String email, String password) {
        String cleanEmail = email == null ? "" : email.strip().toLowerCase();
        if (lockedOut(cleanEmail)) {
            throw new TooManyAttemptsException("too many failed logins for that email, try again in "
                    + LOCKOUT_WINDOW.toMinutes() + " minutes");
        }
        Optional<Account> account = accounts.findByEmail(cleanEmail);
        if (account.isEmpty()) {
            encoder.matches(password == null ? "" : password, DUMMY_HASH);
            recordFailure(cleanEmail);
            return Optional.empty();
        }
        if (password == null || !encoder.matches(password, account.get().getPasswordHash())) {
            recordFailure(cleanEmail);
            return Optional.empty();
        }
        failures.remove(cleanEmail);
        Account found = account.get();
        return Optional.of(new Session(
                tokens.issue(found.getId(), found.getEmail(), found.getName(), found.getRole().name()), found));
    }

    private boolean lockedOut(String email) {
        Failures seen = failures.get(email);
        return seen != null && seen.count() >= MAX_FAILED_LOGINS && withinWindow(seen);
    }

    private void recordFailure(String email) {
        // merge is atomic on a ConcurrentHashMap, so parallel attempts on one email still all get counted.
        failures.merge(email, new Failures(1, Instant.now()),
                (seen, one) -> withinWindow(seen) ? new Failures(seen.count() + 1, seen.since()) : one);
        if (failures.size() > FAILURE_MAP_SWEEP_AT) {
            failures.values().removeIf(seen -> !withinWindow(seen));
        }
    }

    private static boolean withinWindow(Failures seen) {
        return seen.since().isAfter(Instant.now().minus(LOCKOUT_WINDOW));
    }
}
