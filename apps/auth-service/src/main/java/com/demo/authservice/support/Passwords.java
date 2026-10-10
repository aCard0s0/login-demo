package com.demo.authservice.support;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The one place a password is hashed or checked, so the rules and the encoder cannot drift between login and
 * registration.
 *
 * <p>BCrypt reads 72 bytes and, since Spring Security 7, throws past them rather than silently truncating. A
 * long password is therefore refused on the way in and treated as plainly wrong on the way back, never handed
 * to the encoder; and every check costs exactly one hash, so an unknown user or an impossible password takes the
 * same time as a wrong one.
 */
public final class Passwords {

    /** Minimum we are willing to hash. Short passwords are the one input rule worth enforcing here. */
    public static final int MIN_LENGTH = 8;

    /** BCrypt's ceiling. Rejected rather than truncated, or two passwords sharing a 72-byte prefix would be one. */
    public static final int MAX_BYTES = 72;

    /** Compared against when there is nothing real to compare with, so the timing gives nothing away. */
    private static final String DUMMY_HASH = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private static final PasswordEncoder ENCODER = new BCryptPasswordEncoder();

    private Passwords() {}

    /** The hash to store, or 400 if the password breaks a rule. */
    public static String hash(String password) {
        check(password);
        return ENCODER.encode(password);
    }

    /** A hash no password can ever match, for a user that only ever signs in through a provider. */
    public static String unusableHash() {
        return ENCODER.encode(UUID.randomUUID().toString());
    }

    /** Whether the password is the one behind the hash. A null hash (no such user) is a plain no, at the same cost. */
    public static boolean matches(String password, String hash) {
        boolean plausible = password != null && hash != null && password.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES;
        boolean matched = ENCODER.matches(plausible ? password : "", plausible ? hash : DUMMY_HASH);
        return plausible && matched;
    }

    public static void check(String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "password must be at least " + MIN_LENGTH + " characters");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "password must be at most " + MAX_BYTES + " bytes");
        }
    }
}
