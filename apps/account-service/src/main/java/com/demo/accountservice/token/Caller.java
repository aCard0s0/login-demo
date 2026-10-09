package com.demo.accountservice.token;

/**
 * Who is asking, out of the token they sent: an account id, the role auth-service stamped on it, and -- for a
 * token minted for one agent (role {@code AGENT}) -- which agent it is pinned to, else null.
 *
 * <p>An agent token's subject is the agent's owner, so {@code accountId} is always a user; {@code agentId}
 * says whether that user is at the keyboard or one of their agents is. The role is a plain string rather
 * than an enum copied over from auth-service: the two questions below answer "no" for anything
 * unrecognised, so an unknown or missing role lands on least privilege rather than on a crash.
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

    /** How this caller is named on the transfers it makes. */
    public String describe() {
        return isAgent() ? "agent " + agentId : "user " + accountId;
    }
}
