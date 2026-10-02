package com.demo.agentservice.token;

/**
 * Who is asking, out of the token they sent: an account id, the role auth-service stamped on it, and -- for a
 * token minted for one agent (role {@code AGENT}) -- which agent it is pinned to, else null.
 *
 * <p>The role is a plain string rather than an enum copied over from auth-service: the two services share
 * no code on purpose, and the only role this one acts on is {@code AGENT}. Agents are strictly their owner's,
 * so no role reads or writes everyone here.
 */
public record Caller(String accountId, String role, Long agentId) {

    public Caller(String accountId, String role) {
        this(accountId, role, null);
    }

    /** Whether this token may act as the given agent: any of the owner's own tokens, or an agent token for that very agent. */
    public boolean mayActAs(Long agent) {
        return agentId == null || agentId.equals(agent);
    }
}
