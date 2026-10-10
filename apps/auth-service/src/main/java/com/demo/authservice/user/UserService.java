package com.demo.authservice.user;

import com.demo.authservice.support.AttemptWindow;
import com.demo.web.errors.Bad;
import com.demo.authservice.support.Passwords;
import com.demo.authservice.token.Tokens;
import com.demo.authservice.user.entities.AgentTokenVersion;
import com.demo.authservice.user.entities.AgentTokenVersionRepository;
import com.demo.authservice.user.entities.Role;
import com.demo.authservice.user.entities.User;
import com.demo.authservice.user.entities.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Users themselves: who exists, what their details are, and which user a caller's token names.
 *
 * <p>Every refusal is a {@link ResponseStatusException} with the status it means, so the web advice has nothing
 * to translate and a controller can let it through untouched.
 */
@Service
public class UserService {

    /** The width Hibernate gives a String column. Checked here so Postgres does not answer with a misleading 400. */
    private static final int MAX_COLUMN_LENGTH = 255;

    // ponytail: one service-wide cap, not per client. Every request arrives from the Node proxy under one
    // address and the proxy does not set X-Forwarded-For, so per-address would be this anyway; a run of bots
    // fills the window and honest registrations wait it out with them. Per client needs the proxy to set the
    // header and this side to trust it.
    private final AttemptWindow registrations = new AttemptWindow(30, Duration.ofMinutes(15));

    private final UserRepository users;

    private final AgentTokenVersionRepository agentVersions;

    private final Tokens tokens;

    public UserService(UserRepository users, AgentTokenVersionRepository agentVersions, Tokens tokens) {
        this.users = users;
        this.agentVersions = agentVersions;
        this.tokens = tokens;
    }

    // No @Transactional: a transaction takes a pooled connection at entry and would hold it idle through the
    // BCrypt round below. The two statements need no shared transaction; the unique index is the real guard.
    public User register(String name, String email, String password) {
        if (registrations.exceeded("")) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "too many registrations right now, try again in "
                    + registrations.window().toMinutes() + " minutes");
        }
        // Counted before the checks, so a rejected body costs an attempt the same as an accepted one.
        registrations.record("");
        String cleanName = cleanName(name);
        String cleanEmail = cleanEmail(email);
        String hash = Passwords.hash(password);
        // Racing registrations both get past this; the unique index on users.email is what actually decides.
        if (users.existsByEmail(cleanEmail)) {
            throw Bad.request("that email is already registered");
        }
        return users.save(new User(cleanName, cleanEmail, hash));
    }

    /**
     * Changes the caller's own details. The current password is always required, even to change only the name,
     * so a borrowed tab cannot quietly take a user over. A blank newPassword leaves the password alone.
     *
     * <p>A new password also kills every token the user holds, including the one that asked: whoever changes a
     * password is usually doing it because someone else may have the old one, and that someone's session must
     * not outlive it. The controller hands the caller a fresh token in the same answer.
     */
    public User update(Long userId, String name, String email, String currentPassword, String newPassword) {
        // No transaction here on purpose: the one or two BCrypt rounds below take ~100 ms each, and a pooled
        // connection must not sit idle under them. The write is one conditional UPDATE, so nothing read here
        // is written back stale -- a revoke that lands in between keeps its version bump.
        User user = users.findById(userId).orElseThrow(() -> Bad.request("user not found"));
        if (!Passwords.matches(currentPassword, user.getPasswordHash())) {
            throw Bad.request("current password is wrong");
        }
        String cleanName = cleanName(name);
        String cleanEmail = cleanEmail(email);
        if (!cleanEmail.equals(user.getEmail()) && users.existsByEmail(cleanEmail)) {
            throw Bad.request("that email is already registered");
        }
        boolean changingPassword = newPassword != null && !newPassword.isBlank();
        String hash = changingPassword ? Passwords.hash(newPassword) : user.getPasswordHash();
        if (users.edit(userId, cleanName, cleanEmail, user.getPasswordHash(), hash, changingPassword ? 1 : 0) == 0) {
            // The password changed under us, so the one we just checked is no longer current.
            throw Bad.request("current password is wrong");
        }
        return users.findById(userId).orElseThrow(() -> Bad.request("user not found"));
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
    public User findOrCreateFromOAuth(String email, String name) {
        String cleanEmail = cleanEmail(email);
        return users.findByEmail(cleanEmail).orElseGet(() ->
                // A hash of a value nobody holds, rather than a nullable column every password path would then
                // have to test for. No password can match it, so the only way in stays the provider.
                users.save(new User(cleanName(nameOr(name, cleanEmail)), cleanEmail, Passwords.unusableHash())));
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
            throw Bad.request("agentId is required");
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
        // One upsert rather than read-modify-write: two revokes landing together would otherwise race on the
        // first insert and answer one of them with a misleading 400, or lose a bump.
        agentVersions.bump(agentId);
        return agentVersions.findById(agentId).map(AgentTokenVersion::getVersion).orElse(0);
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
            throw Bad.request("a role is required");
        }
        if (admin.getId().equals(userId) && role != Role.ADMIN) {
            throw Bad.request("an admin cannot take its own admin rights away");
        }
        User user = users.findById(userId).orElseThrow(() -> Bad.request("user not found"));
        if (user.getRole() != role) {
            // The role rides inside the token and the other services read it from there, not from this row:
            // without this a demoted admin keeps writing everyone's wallets until its token expires.
            user.setTokenVersion(user.getTokenVersion() + 1);
        }
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
            throw Bad.request("an admin cannot suspend itself");
        }
        User user = users.findById(userId).orElseThrow(() -> Bad.request("user not found"));
        if (suspended && !user.isSuspended()) {
            user.setTokenVersion(user.getTokenVersion() + 1);
        }
        user.setSuspended(suspended);
        return users.save(user);
    }

    /** Signs a user out everywhere: every token it holds stops working. It can log straight back in. */
    @Transactional
    public User revokeTokens(Long userId) {
        User user = users.findById(userId).orElseThrow(() -> Bad.request("user not found"));
        user.setTokenVersion(user.getTokenVersion() + 1);
        return users.save(user);
    }

    /**
     * Every user that has had its tokens revoked, by id, with the version a token must carry to count -- and
     * every agent likewise, under {@code agent:<id>}, so the two kinds of key can never collide in one map.
     */
    public Map<String, Integer> tokenVersions() {
        Map<String, Integer> all = new HashMap<>();
        // Projections, not entities: three services poll this every ten seconds and only the two columns travel.
        users.findByTokenVersionGreaterThan(0).forEach(a -> all.put(String.valueOf(a.id()), a.tokenVersion()));
        agentVersions.findByVersionGreaterThan(0).forEach(v -> all.put("agent:" + v.agentId(), v.version()));
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
        User admin = new User("Admin", cleanEmail, Passwords.hash(password));
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
            throw Bad.request("name is required");
        }
        if (clean.length() > MAX_COLUMN_LENGTH) {
            throw Bad.request("name must be at most " + MAX_COLUMN_LENGTH + " characters");
        }
        return clean;
    }

    private static String cleanEmail(String email) {
        String clean = email == null ? "" : email.strip().toLowerCase();
        if (clean.length() > MAX_COLUMN_LENGTH || !clean.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw Bad.request("a valid email is required");
        }
        return clean;
    }

}
