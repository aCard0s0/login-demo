# jpa-crypto

One text column encrypted at rest, and the migration that catches up rows written before it was. A plain
jar: no port, no database, no main class. agent-service uses it for the authorization header an owner stores
on an MCP server row; the next secret-bearing column is the next consumer.

```
com.demo.jpa.crypto   EncryptedText              AttributeConverter<String,String>: AES-256-GCM, key = SHA-256 of the configured secret
                      EncryptedColumnMigration   rewrites every row of one column that lacks the v1: prefix; idempotent
```

## Using it

Subclass the converter once per column, so the key's property lives with the service, and keep the subclass
both a Spring bean and a `@Converter` -- Hibernate then builds it through Spring and the `@Value` is honoured:

```java
@Component
@Converter
public class AuthHeaderCrypto extends EncryptedText {
    public AuthHeaderCrypto(@Value("${agents.auth-header-key:}") String key) { super(key); }
}

@Convert(converter = AuthHeaderCrypto.class)
@Column(length = 2000)
private String authHeader;
```

Plain text grows to `3 + 4/3 * (12 + length + 16)` characters once encrypted; size the column for that, and
refuse longer input before it reaches the database.

A blank key refuses to start rather than quietly storing plain text. Each stored value is `v1:` plus a fresh
nonce and the ciphertext, so equal values never look alike on disk, and a tampered row fails to decrypt rather
than coming back wrong. A value without the prefix is read as it is: that is a row from before encryption
existed, and the migration -- run once at startup from an `ApplicationRunner` -- rewrites every such row:

```java
new EncryptedColumnMigration(jdbcClient, crypto, "agent_mcp_servers", "id", "auth_header").migrate();
```

## Limitation, on purpose

One key, no rotation. Changing it makes every stored value unreadable, so it belongs with the database. The
`v1:` prefix is what a second key version would key on.

## Tests

`EncryptedTextTests`: the round trip, a fresh nonce per write, the wrong key, a tampered row, a blank key.
`EncryptedColumnMigrationTests`: against an in-memory SQLite, only plain rows are rewritten, NULL and encrypted
rows are left alone, a second run does nothing, and the table and column names are checked to be plain
identifiers.
