package com.demo.authservice.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Puts the deployment's admin in the database on startup, from ADMIN_EMAIL and ADMIN_PASSWORD.
 *
 * <p>Not in docker/initdb.sql, where the rest of the database setup lives, because the password has to be
 * BCrypt hashed and SQL cannot hash it the way this service does. Running it here also means the admin is
 * re-checked on every boot rather than only on the very first one, when the volume was empty.
 *
 * <p>Missing credentials are a warning, not a failure: `./mvnw test` and a bare `spring-boot:run` have no
 * .env to read and do not need an admin. Compose is where they are required, and it refuses to start
 * without them.
 */
@Component
public class AdminSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminSeeder.class);

    private final AccountService accounts;

    private final String email;

    private final String password;

    public AdminSeeder(AccountService accounts,
                       @Value("${admin.email:}") String email,
                       @Value("${admin.password:}") String password) {
        this.accounts = accounts;
        this.email = email;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (email.isBlank() || password.isBlank()) {
            log.warn("no admin.email/admin.password set — starting with no admin account");
            return;
        }
        Account admin = accounts.ensureAdmin(email, password);
        // The address, never the password: this line goes to the container logs.
        log.info("admin account ready: {}", admin.getEmail());
    }
}
