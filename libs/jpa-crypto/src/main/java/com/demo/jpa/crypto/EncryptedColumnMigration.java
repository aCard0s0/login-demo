package com.demo.jpa.crypto;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Encrypts every value in one column that was written before {@link EncryptedText} guarded it. Plain SQL
 * rather than the entities: an entity whose plain-text value does not change is not dirty, so saving it would
 * write nothing. Idempotent, since an encrypted value carries the prefix and is not selected. Run it once at
 * startup, from an {@code ApplicationRunner}.
 */
public final class EncryptedColumnMigration {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final JdbcClient db;

    private final EncryptedText crypto;

    private final String select;

    private final String update;

    public EncryptedColumnMigration(JdbcClient db, EncryptedText crypto, String table, String idColumn, String column) {
        for (String name : new String[] {table, idColumn, column}) {
            if (!IDENTIFIER.matcher(name).matches()) {
                throw new IllegalArgumentException("not a plain SQL identifier: " + name);
            }
        }
        this.db = db;
        this.crypto = crypto;
        this.select = "SELECT " + idColumn + " AS id, " + column + " AS value FROM " + table + " WHERE " + column + " IS NOT NULL AND " + column + " NOT LIKE ?";
        this.update = "UPDATE " + table + " SET " + column + " = ? WHERE " + idColumn + " = ?";
    }

    /** How many rows were rewritten. */
    public int migrate() {
        int count = 0;
        for (Map<String, Object> row : db.sql(select).param(EncryptedText.PREFIX + "%").query().listOfRows()) {
            db.sql(update).param(crypto.encrypt(String.valueOf(row.get("value")))).param(row.get("id")).update();
            count++;
        }
        return count;
    }
}
