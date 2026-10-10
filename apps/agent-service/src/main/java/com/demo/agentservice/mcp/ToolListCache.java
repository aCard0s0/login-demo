package com.demo.agentservice.mcp;

import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 */
// ponytail: one process-wide map, evicted lazily on read and cleared outright past a size cap. Enough for a demo;
// a bounded LRU is the upgrade if the number of distinct servers ever matters.
final class ToolListCache {

    static final int MAX_ENTRIES = 1000;

    private record Entry(Instant at, List<Tool> tools) {}

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    private final Duration ttl;

    private final Supplier<Instant> clock;

    ToolListCache(Duration ttl, Supplier<Instant> clock) {
        this.ttl = ttl;
        this.clock = clock;
    }

    /** The cached list, or the one {@code load} produces, which is then kept for the TTL. A failed load caches nothing. */
    List<Tool> get(String url, String credential, Supplier<List<Tool>> load) {
        String key = url + " " + sha256(credential == null ? "" : credential);
        Entry entry = entries.get(key);
        if (entry != null && entry.at().plus(ttl).isAfter(clock.get())) {
            return entry.tools();
        }
        List<Tool> tools = List.copyOf(load.get());
        if (entries.size() >= MAX_ENTRIES) {
            entries.clear();
        }
        entries.put(key, new Entry(clock.get(), tools));
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
