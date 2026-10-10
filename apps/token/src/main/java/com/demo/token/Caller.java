package com.demo.token;

/**
 * Who is asking, out of the token they sent: an account id, the role auth-service stamped on it, and -- for a
 * token minted for one agent (role {@code AGENT}) -- which agent it is pinned to, else null.
 *
 * <p>An agent token's subject is the agent's owner, so {@code accountId} is always a user; {@code agentId}
 * says whether that user is at the keyboard or one of their agents is. The role is a plain string rather
 * than an enum copied over from auth-service: every question below answers "no" for anything unrecognised,
 * so an unknown or missing role lands on least privilege rather than on a crash.
 */
public record Caller(String accountId, String role, Long agentId) {

    public Caller(String accountId, String role) {
        this(accountId, role, null);
    }

    public boolean isAgent() {
        return agentId != null;
    }

    public boolean readsEveryone() {
        return "ADMIN".equals(role) || "MODERATOR".equals(role);
    }

    public boolean writesEveryone() {
        return "ADMIN".equals(role);
    }

    /** Whether this token may act as the given agent: any of the owner's own tokens, or an agent token for that very agent. */
    public boolean mayActAs(Long agent) {
        return agentId == null || agentId.equals(agent);
    }

    /** How this caller is named on the records it writes. */
    public String describe() {
        return isAgent() ? "agent " + agentId : "user " + accountId;
    }
}
