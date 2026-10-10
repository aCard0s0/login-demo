package com.demo.authservice.session;

import com.demo.authservice.support.AttemptWindow;
import com.demo.authservice.support.Passwords;
import com.demo.authservice.token.Tokens;
import com.demo.authservice.user.entities.User;
import com.demo.authservice.user.entities.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.Optional;

/** Turning a password into a token, and refusing to keep trying once an email has failed too often. */
@Service
public class SessionService {

    // ponytail: per email, not per IP, because every browser request arrives from the Node proxy and would share
    // one counter. The cost is that someone can lock a known address out on purpose; per-IP needs X-Forwarded-For.
    private final AttemptWindow failures = new AttemptWindow(5, Duration.ofMinutes(15));

    private final UserRepository users;

    private final Tokens tokens;

    public SessionService(UserRepository users, Tokens tokens) {
        this.users = users;
        this.tokens = tokens;
    }

    /** A session, or empty for bad credentials. 429 once the email has failed too often, 403 if the user is suspended. */
    public Optional<Session> login(String email, String password) {
        String cleanEmail = email == null ? "" : email.strip().toLowerCase();
        if (failures.exceeded(cleanEmail)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "too many failed logins for that email, try again in "
                    + failures.window().toMinutes() + " minutes");
        }
        Optional<User> user = users.findByEmail(cleanEmail);
        // One hash either way: a missing user or an impossible password costs the same as a wrong one.
        if (!Passwords.matches(password, user.map(User::getPasswordHash).orElse(null))) {
            failures.record(cleanEmail);
            return Optional.empty();
        }
        failures.clear(cleanEmail);
        User found = user.orElseThrow();
        // Only after the password checks out, so a stranger cannot use this to learn who is suspended.
        if (found.isSuspended()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "this user is suspended");
        }
        return Optional.of(new Session(tokens.issue(found.getId(), found.getEmail(), found.getName(),
                found.getRole().name(), found.getTokenVersion()), found));
    }
}
