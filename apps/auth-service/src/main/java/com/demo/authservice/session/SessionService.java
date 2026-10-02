package com.demo.authservice.session;

import com.demo.authservice.account.Account;
import com.demo.authservice.account.AccountRepository;
import com.demo.authservice.support.AttemptWindow;
import com.demo.authservice.support.TooManyAttemptsException;
import com.demo.authservice.token.Tokens;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.Optional;

/** Turning a password into a token, and refusing to keep trying once an email has failed too often. */
@Service
public class SessionService {

    /** Compared against when the email is unknown, so a missing account costs the same time as a wrong password. */
    private static final String DUMMY_HASH = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    // ponytail: per email, not per IP, because every browser request arrives from the Node proxy and would share
    // one counter. The cost is that someone can lock a known address out on purpose; per-IP needs X-Forwarded-For.
    private final AttemptWindow failures = new AttemptWindow(5, Duration.ofMinutes(15));

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    private final AccountRepository accounts;

    private final Tokens tokens;

    public SessionService(AccountRepository accounts, Tokens tokens) {
        this.accounts = accounts;
        this.tokens = tokens;
    }

    public Optional<Session> login(String email, String password) {
        String cleanEmail = email == null ? "" : email.strip().toLowerCase();
        // Empty rather than null, so a missing password still costs one hash and cannot tell an account apart.
        String cleanPassword = password == null ? "" : password;
        if (failures.exceeded(cleanEmail)) {
            throw new TooManyAttemptsException("too many failed logins for that email, try again in "
                    + failures.window().toMinutes() + " minutes");
        }
        Optional<Account> account = accounts.findByEmail(cleanEmail);
        if (account.isEmpty()) {
            encoder.matches(cleanPassword, DUMMY_HASH);
            failures.record(cleanEmail);
            return Optional.empty();
        }
        if (!encoder.matches(cleanPassword, account.get().getPasswordHash())) {
            failures.record(cleanEmail);
            return Optional.empty();
        }
        failures.clear(cleanEmail);
        Account found = account.get();
        // Only after the password checks out, so a stranger cannot use this to learn who is suspended.
        if (found.isSuspended()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "this account is suspended");
        }
        return Optional.of(new Session(tokens.issue(found.getId(), found.getEmail(), found.getName(),
                found.getRole().name(), found.getTokenVersion()), found));
    }
}
