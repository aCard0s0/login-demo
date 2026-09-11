package com.demo.authservice.account;

/**
 * What an account is allowed to do, in both services. The role travels inside the token, so todo-service
 * can answer "may this caller see everybody's todos?" without ever calling back here.
 *
 * <p>Deliberately a flat list rather than a permission table: four roles with fixed answers is the whole
 * requirement, and a table would be a schema to maintain for rules that are currently two booleans.
 */
public enum Role {

    /** Reads and writes everything, everywhere, and is the only role that can change another account's role. */
    ADMIN,

    /** Reads everyone. Writes only its own data, exactly like a user. */
    MODERATOR,

    /** Reserved, and currently identical to USER. Nothing grants it yet. */
    AGENT,

    /** Reads and writes its own data and nothing else. What every registration starts as. */
    USER;

    /** Whether this role sees other accounts' data. The question todo-service asks about a list. */
    public boolean readsEveryone() {
        return this == ADMIN || this == MODERATOR;
    }

    /** Whether this role changes other accounts' data. Only the admin does. */
    public boolean writesEveryone() {
        return this == ADMIN;
    }
}
