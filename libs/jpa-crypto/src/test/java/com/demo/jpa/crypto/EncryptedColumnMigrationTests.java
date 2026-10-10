package com.demo.jpa.crypto;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Against an in-memory SQLite: the plain rows are rewritten, the encrypted and the empty ones are left alone, and running it again does nothing. */
class EncryptedColumnMigrationTests {

    final JdbcClient db = JdbcClient.create(new SingleConnectionDataSource("jdbc:sqlite::memory:", true));

    final EncryptedText crypto = new EncryptedText("key");

    @Test
    void encryptsOnlyThePlainRowsAndIsIdempotent() {
        db.sql("CREATE TABLE servers (id INTEGER PRIMARY KEY, auth_header TEXT)").update();
        db.sql("INSERT INTO servers (id, auth_header) VALUES (1, 'Bearer legacy'), (2, NULL), (3, ?), (4, 'Bearer other')")
                .param(crypto.encrypt("Bearer already")).update();

        EncryptedColumnMigration migration = new EncryptedColumnMigration(db, crypto, "servers", "id", "auth_header");
        assertEquals(2, migration.migrate());
        assertEquals(0, migration.migrate(), "nothing left: it is idempotent");

        List<String> stored = db.sql("SELECT auth_header FROM servers ORDER BY id").query(String.class).list();
        assertEquals(4, stored.size());
        assertNull(stored.get(1), "the NULL row stays NULL");
        assertTrue(stored.stream().filter(Objects::nonNull).allMatch(s -> s.startsWith(EncryptedText.PREFIX)), stored.toString());
        assertEquals(List.of("Bearer legacy", "Bearer already", "Bearer other"),
                stored.stream().filter(Objects::nonNull).map(crypto::decrypt).toList());
    }

    @Test
    void refusesAnythingButAPlainIdentifierSoNoCallerCanSmuggleSqlIntoTheStatement() {
        assertThrows(IllegalArgumentException.class, () -> new EncryptedColumnMigration(db, crypto, "servers; DROP TABLE x", "id", "auth_header"));
        assertThrows(IllegalArgumentException.class, () -> new EncryptedColumnMigration(db, crypto, "servers", "id", "auth header"));
    }
}
