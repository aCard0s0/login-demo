package com.demo.agentservice.agent;

import com.demo.jpa.crypto.EncryptedColumnMigration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Encrypts every {@code authHeader} written before {@link AuthHeaderCrypto} existed, once, at startup. Idempotent. */
@Component
public class AuthHeaderMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AuthHeaderMigration.class);

    private final EncryptedColumnMigration migration;

    public AuthHeaderMigration(JdbcClient db, AuthHeaderCrypto crypto) {
        this.migration = new EncryptedColumnMigration(db, crypto, "agent_mcp_servers", "id", "auth_header");
    }

    @Override
    public void run(ApplicationArguments args) {
        int migrated = migrate();
        if (migrated > 0) {
            log.info("encrypted {} stored authorization header(s) that were plain text", migrated);
        }
    }

    public int migrate() {
        return migration.migrate();
    }
}
