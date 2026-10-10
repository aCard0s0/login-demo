package com.demo.agentservice.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Encrypts every {@code authHeader} written before {@link AuthHeaderCrypto} existed, once, at startup. Plain
 * SQL rather than the entities: an entity whose plain-text value does not change is not dirty, so saving it
 * would write nothing. Idempotent, since an encrypted value carries the prefix and is not selected.
 */
@Component
public class AuthHeaderMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AuthHeaderMigration.class);

    private final JdbcClient db;

    private final AuthHeaderCrypto crypto;

    public AuthHeaderMigration(JdbcClient db, AuthHeaderCrypto crypto) {
        this.db = db;
        this.crypto = crypto;
    }

    @Override
    public void run(ApplicationArguments args) {
        int migrated = migrate();
        if (migrated > 0) {
            log.info("encrypted {} stored authorization header(s) that were plain text", migrated);
        }
    }

    public int migrate() {
        int count = 0;
        for (Map<String, Object> row : db.sql("SELECT id, auth_header FROM agent_mcp_servers WHERE auth_header IS NOT NULL AND auth_header NOT LIKE ?")
                .param(AuthHeaderCrypto.PREFIX + "%").query().listOfRows()) {
            db.sql("UPDATE agent_mcp_servers SET auth_header = ? WHERE id = ?")
                    .param(crypto.encrypt(String.valueOf(row.get("auth_header")))).param(row.get("id")).update();
            count++;
        }
        return count;
    }
}
