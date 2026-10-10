package com.demo.agentservice.mcp;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
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
 * <p>A Caffeine cache: entries expire by age and the least recently used go first past the size cap. The load runs
 * outside it, so a slow server holds up nobody else's listing; two requests missing the same key at once may
 * both connect. A failed load caches nothing.
 */
final class ToolListCache {

    static final int MAX_ENTRIES = 1000;

    private final Cache<String, List<Tool>> entries;

    /** {@code ticker} is what the TTL is measured by: the tests move it, the service passes {@link Ticker#systemTicker()}. */
    ToolListCache(Duration ttl, Ticker ticker) {
        this.entries = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(MAX_ENTRIES)
                .ticker(ticker)
                .build();
    }

    /** The cached list, or the one {@code load} produces, which is then kept for the TTL. */
    List<Tool> get(String url, String credential, Supplier<List<Tool>> load) {
        String key = url + " " + sha256(credential == null ? "" : credential);
        List<Tool> tools = entries.getIfPresent(key);
        if (tools == null) {
            // Not entries.get(key, load): Caffeine runs that under a lock other keys can share, for the whole connection.
            tools = List.copyOf(load.get());
            entries.put(key, tools);
        }
        return tools;
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
