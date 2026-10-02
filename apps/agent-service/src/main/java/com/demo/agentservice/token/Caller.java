package com.demo.agentservice.token;

/**
 * Who is asking, out of the token they sent: an account id, the role auth-service stamped on it, and -- for a
 * token minted for one agent (role {@code AGENT}) -- which agent it is pinned to, else null.
 *
 * <p>The role is a plain string rather than an enum copied over from auth-service. The two services share
 * no code on purpose, and this one only ever asks two questions of it -- both of which answer "no" for
 * anything unrecognised, so an unknown or missing role lands on least privilege rather than on a crash.
 */
public record Caller(String accountId, String role, Long agentId) {

    public Caller(String accountId, String role) {
        this(accountId, role, null);
    }

    /** Whether this token may act as the given agent: any of the owner's own tokens, or an agent token for that very agent. */
    public boolean mayActAs(Long agent) {
        return agentId == null || agentId.equals(agent);
    }

    public boolean readsEveryone() {
        return "ADMIN".equals(role) || "MODERATOR".equals(role);
    }

    public boolean writesEveryone() {
        return "ADMIN".equals(role);
    }
}
