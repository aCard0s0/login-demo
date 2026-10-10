package com.demo.authservice.user.entities;

/**
 * What a user is allowed to do, in both services. The role travels inside the token, so todo-service
 * can answer "may this caller see everybody's todos?" without ever calling back here.
 *
 * <p>Deliberately a flat list rather than a permission table: three roles with fixed answers is the whole
 * requirement, and a table would be a schema to maintain for rules that are currently two booleans.
 *
 * <p>No AGENT here: an agent is not a user and never holds a role. The {@code "AGENT"} a token minted for
 * one carries is a plain string {@link com.demo.authservice.token.Tokens} stamps, not a value of this enum.
 */
public enum Role {

    /** Reads and writes everything, everywhere, and is the only role that can change another user's role. */
    ADMIN,

    /** Reads everyone. Writes only its own data, exactly like a user. */
    MODERATOR,

    /** Reads and writes its own data and nothing else. What every registration starts as. */
    USER;

    /** Whether this role sees other users' data. The question todo-service asks about a list. */
    public boolean readsEveryone() {
        return this == ADMIN || this == MODERATOR;
    }

    /** Whether this role changes other users' data. Only the admin does. */
    public boolean writesEveryone() {
        return this == ADMIN;
    }
}
