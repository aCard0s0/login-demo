package com.demo.agentservice.mcp;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The tool list of a downstream server, kept for a short while so that {@code tools/list} does not connect to
 * every server and {@code tools/call} does not list before it calls. Keyed by the server's URL and a SHA-256
 * of the credential sent to it -- never the credential itself -- so two agents forwarding different tokens
 * never see each other's listing, and the raw token is nowhere in memory but the request.
 *
 * <p>Only the server's raw listing is cached. What the agent is offered and allowed is decided after, from the
 * agent row as it is right now, so the cache never extends a permission; and the URL policy runs before, so it
 * never skips the connect-time check either.
 *
 * <p>A Caffeine cache: entries expire by age, the least recently used go first past the size cap, and two
 * requests missing the same key at once make one connection, not two. A failed load caches nothing.
 */
final class ToolListCache {

    static final int MAX_ENTRIES = 1000;

    private final Cache<String, List<Tool>> entries;

    /** {@code clock} is what the TTL is measured by: the tests move it, the service passes {@code Instant::now}. */
    ToolListCache(Duration ttl, Supplier<Instant> clock) {
        this.entries = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(MAX_ENTRIES)
                .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.get().toEpochMilli()))
                .build();
    }

    /** The cached list, or the one {@code load} produces, which is then kept for the TTL. */
    List<Tool> get(String url, String credential, Supplier<List<Tool>> load) {
        String key = url + " " + sha256(credential == null ? "" : credential);
        return entries.get(key, k -> List.copyOf(load.get()));
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
