package com.demo.agentservice.mcp;

import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolListCacheTests {

    final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
    final ToolListCache cache = new ToolListCache(Duration.ofSeconds(15), now::get);
    final AtomicInteger loads = new AtomicInteger();

    private List<Tool> load(String url, String credential) {
        return cache.get(url, credential, () -> {
            loads.incrementAndGet();
            return List.of(Tool.builder().name("t" + loads.get()).description("").inputSchema(Map.of("type", "object")).build());
        });
    }

    @Test
    void oneListingPerUrlAndCredentialWithinTheTtl() {
        assertEquals("t1", load("http://a/mcp", "Bearer x").get(0).name());
        assertEquals("t1", load("http://a/mcp", "Bearer x").get(0).name(), "within the TTL the same answer, no load");
        assertEquals("t2", load("http://a/mcp", "Bearer y").get(0).name(), "another credential is another listing");
        assertEquals("t3", load("http://b/mcp", "Bearer x").get(0).name(), "another server too");
        assertEquals(3, loads.get());

        now.set(now.get().plusSeconds(16));
        assertEquals("t4", load("http://a/mcp", "Bearer x").get(0).name(), "past the TTL it is listed again");

        AtomicInteger failed = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> cache.get("http://dead/mcp", null, () -> {
            failed.incrementAndGet();
            throw new IllegalStateException("down");
        }));
        assertThrows(IllegalStateException.class, () -> cache.get("http://dead/mcp", null, () -> {
            failed.incrementAndGet();
            throw new IllegalStateException("down");
        }));
        assertEquals(2, failed.get(), "a failure is not cached: the next listing tries again");
    }

    @Test
    void theKeyHoldsAHashOfTheCredentialNeverTheCredential() {
        assertEquals(64, ToolListCache.sha256("Bearer x").length());
        assertFalse(ToolListCache.sha256("Bearer secret-token").contains("secret"));
    }
}
