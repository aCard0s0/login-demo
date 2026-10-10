package com.demo.authservice.user;

import com.demo.authservice.support.AttemptWindow;
import com.demo.authservice.support.TooManyAttemptsException;
import com.demo.authservice.token.Tokens;
import com.demo.authservice.user.entities.AgentTokenVersion;
import com.demo.authservice.user.entities.AgentTokenVersionRepository;
import com.demo.authservice.user.entities.Role;
import com.demo.authservice.user.entities.User;
import com.demo.authservice.user.entities.UserRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Users themselves: who exists, what their details are, and which user a caller's token names. */
@Service
public class UserService {

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

    private final UserRepository users;

    private final AgentTokenVersionRepository agentVersions;

    private final Tokens tokens;

    public UserService(UserRepository users, AgentTokenVersionRepository agentVersions, Tokens tokens) {
        this.users = users;
        this.agentVersions = agentVersions;
        this.tokens = tokens;
    }

    @Transactional
    public User register(String name, String email, String password) {
        if (registrations.exceeded("")) {
            throw new TooManyAttemptsException("too many registrations right now, try again in "
                    + registrations.window().toMinutes() + " minutes");
        }
        // Counted before the checks, so a rejected body costs an attempt the same as an accepted one.
        registrations.record("");
        String cleanName = cleanName(name);
        String cleanEmail = cleanEmail(email);
        checkPassword(password);
        // Racing registrations both get past this; the unique index on users.email is what actually decides.
        if (users.existsByEmail(cleanEmail)) {
            throw new IllegalArgumentException("that email is already registered");
        }
        return users.save(new User(cleanName, cleanEmail, encoder.encode(password)));
    }

    /**
     * Changes the caller's own details. The current password is always required, even to change only the name,
     * so a borrowed tab cannot quietly take a user over. A blank newPassword leaves the password alone.
     */
    @Transactional
    public User update(Long userId, String name, String email, String currentPassword, String newPassword) {
        User user = users.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("user not found"));
        if (currentPassword == null || !encoder.matches(currentPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("current password is wrong");
        }
        String cleanName = cleanName(name);
        String cleanEmail = cleanEmail(email);
        if (!cleanEmail.equals(user.getEmail()) && users.existsByEmail(cleanEmail)) {
            throw new IllegalArgumentException("that email is already registered");
        }
        user.setName(cleanName);
        user.setEmail(cleanEmail);
        if (newPassword != null && !newPassword.isBlank()) {
            checkPassword(newPassword);
            user.setPasswordHash(encoder.encode(newPassword));
        }
        return users.save(user);
    }

    /**
     * The user behind a provider identity: the one already registered with that email, or a new one.
     *
     * <p>Matching on the email is what makes signing in with Google and signing in with a password the same
     * user rather than two. The caller is responsible for having checked that the provider verified the
     * address -- this method cannot tell, and an unverified one would be a way to walk into somebody else's
     * user.
     */
    // ponytail: no provider/provider-id columns, so a user created this way has no password it can be
    // asked for, and /profile's "current password" gate locks its owner out of editing. A "set a password"
    // flow is the upgrade; linking by verified email is the whole requirement today.
    @Transactional
    public User findOrCreateFromOAuth(String email, String name) {
        String cleanEmail = cleanEmail(email);
        return users.findByEmail(cleanEmail).orElseGet(() -> {
            // A hash of a value nobody holds, rather than a nullable column every password path would then
            // have to test for. No password can match it, so the only way in stays the provider.
            String unusable = encoder.encode(UUID.randomUUID().toString());
            return users.save(new User(cleanName(nameOr(name, cleanEmail)), cleanEmail, unusable));
        });
    }

    /**
     * Resolves a token to the user it names, or empty if it does not check out. The user is re-read rather
     * than taken from the token's claims, so a rename shows up straight away instead of at the next login, and
     * a suspension or a revocation bites on the very next request.
     *
     * <p>An agent token is 403, not a user: its only way in is {@code /mcp} through agent-service, and here
     * it could edit its owner's email and password. Judged after validity, so a dead one is still a plain 401.
     */
    public Optional<User> byToken(String token) {
        return tokens.claimsFrom(token).flatMap(claims -> users.findById(claims.userId())
                .filter(user -> !user.isSuspended() && user.getTokenVersion() == claims.version())
                .map(user -> {
                    if (claims.agent()) {
                        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "an agent token can only connect to /mcp");
                    }
                    return user;
                }));
    }

    /** A token for the user, stamped with its current version so a later revocation can kill it. */
    public String issue(User user) {
        return tokens.issue(user.getId(), user.getEmail(), user.getName(), user.getRole().name(),
                user.getTokenVersion());
    }

    /**
     * A long-lived token one of the user's agents connects to agent-service with. agent-service asks for it
     * on the owner's behalf over the compose network; the token names the owner and the one agent, and dies
     * with the owner's other tokens on revoke or suspend -- or alone, on {@link #revokeAgentTokens}.
     */
    public String issueAgentToken(Long userId, Long agentId) {
        if (agentId == null) {
            throw new IllegalArgumentException("agentId is required");
        }
        User user = users.findById(userId == null ? -1 : userId).filter(a -> !a.isSuspended())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found"));
        int agentVersion = agentVersions.findById(agentId).map(AgentTokenVersion::getVersion).orElse(0);
        return tokens.issueForAgent(user.getId(), user.getEmail(), user.getName(), user.getTokenVersion(), agentId, agentVersion);
    }

    /**
     * Kills every token minted for one agent, and nothing else: the owner's login and their other agents' tokens
     * live on. agent-service asks, after checking the caller owns the agent; this side only counts.
     */
    @Transactional
    public int revokeAgentTokens(Long agentId) {
        AgentTokenVersion version = agentVersions.findById(agentId).orElseGet(() -> new AgentTokenVersion(agentId));
        version.setVersion(version.getVersion() + 1);
        return agentVersions.save(version).getVersion();
    }

    /** How many users exist. Public: a count gives away nothing about who they are. */
    public long count() {
        return users.count();
    }

    /** Every user. Only ever reached by a role that {@link Role#readsEveryone()}; the controller checks that. */
    public List<User> all() {
        return users.findAll();
    }

    /**
     * Moves a user to another role. The caller is passed in so the one user that could lock everybody
     * out -- an admin demoting itself, leaving nobody able to promote anyone again -- is refused here rather
     * than relying on whoever writes the next controller to remember.
     */
    @Transactional
    public User changeRole(User admin, Long userId, Role role) {
        if (role == null) {
            throw new IllegalArgumentException("a role is required");
        }
        if (admin.getId().equals(userId) && role != Role.ADMIN) {
            throw new IllegalArgumentException("an admin cannot take its own admin rights away");
        }
        User user = users.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("user not found"));
        user.setRole(role);
        return users.save(user);
    }

    /**
     * Suspends or reactivates a user. Suspending also revokes, so the tokens it holds die with it rather
     * than at their expiry. An admin cannot suspend itself, for the same reason it cannot demote itself.
     */
    @Transactional
    public User setSuspended(User admin, Long userId, boolean suspended) {
        if (admin.getId().equals(userId) && suspended) {
            throw new IllegalArgumentException("an admin cannot suspend itself");
        }
        User user = users.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("user not found"));
        if (suspended && !user.isSuspended()) {
            user.setTokenVersion(user.getTokenVersion() + 1);
        }
        user.setSuspended(suspended);
        return users.save(user);
    }

    /** Signs a user out everywhere: every token it holds stops working. It can log straight back in. */
    @Transactional
    public User revokeTokens(Long userId) {
        User user = users.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("user not found"));
        user.setTokenVersion(user.getTokenVersion() + 1);
        return users.save(user);
    }

    /**
     * Every user that has had its tokens revoked, by id, with the version a token must carry to count -- and
     * every agent likewise, under {@code agent:<id>}, so the two kinds of key can never collide in one map.
     */
    public Map<String, Integer> tokenVersions() {
        Map<String, Integer> all = new HashMap<>();
        users.findByTokenVersionGreaterThan(0).forEach(a -> all.put(String.valueOf(a.getId()), a.getTokenVersion()));
        agentVersions.findByVersionGreaterThan(0).forEach(v -> all.put("agent:" + v.getAgentId(), v.getVersion()));
        return all;
    }

    /**
     * Makes sure the deployment's admin exists, at startup, from the credentials in the environment.
     *
     * <p>Create-only on purpose. If the address is already registered it is promoted but its password is left
     * exactly as it is, so a restart can never quietly reset a password the admin has since changed, and a
     * stale value left in .env cannot hand the user back to whoever last read that file.
     */
    @Transactional
    public User ensureAdmin(String email, String password) {
        String cleanEmail = cleanEmail(email);
        Optional<User> existing = users.findByEmail(cleanEmail);
        if (existing.isPresent()) {
            User admin = existing.get();
            if (admin.getRole() != Role.ADMIN) {
                admin.setRole(Role.ADMIN);
                return users.save(admin);
            }
            return admin;
        }
        checkPassword(password);
        User admin = new User("Admin", cleanEmail, encoder.encode(password));
        admin.setRole(Role.ADMIN);
        return users.save(admin);
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
