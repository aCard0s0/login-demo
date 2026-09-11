package com.demo.todoservice.token;

/**
 * Who is asking, out of the token they sent: an account id and the role auth-service stamped on it.
 *
 * <p>The role is a plain string rather than an enum copied over from auth-service. The two services share
 * no code on purpose, and this one only ever asks two questions of it -- both of which answer "no" for
 * anything unrecognised, so an unknown or missing role lands on least privilege rather than on a crash.
 */
public record Caller(String accountId, String role) {

    public boolean readsEveryone() {
        return "ADMIN".equals(role) || "MODERATOR".equals(role);
    }

    public boolean writesEveryone() {
        return "ADMIN".equals(role);
    }
}
