package com.demo.auth.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The feed is read on a caller's request, so it must be cached, and an auth-service that is down must not take this one with it. */
class RevocationsTests {

    @Test
    void theFeedIsReadOnceAWindowNotOnceARequest() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        HttpServer auth = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        auth.createContext("/token-versions", exchange -> {
            reads.incrementAndGet();
            byte[] body = "{\"7\":2,\"agent:9\":3}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        auth.start();
        try {
            Revocations revocations = new Revocations("http://localhost:" + auth.getAddress().getPort() + "/token-versions");
            assertEquals(2, revocations.minimumVersion("7"));
            assertEquals(3, revocations.minimumAgentVersion(9L));
            assertEquals(0, revocations.minimumVersion("8"), "a user never revoked needs no version");
            assertEquals(0, revocations.minimumVersion("9"), "an agent's entry is not a user's: agent:9 is not 9");
            assertEquals(1, reads.get(), "four questions, one read");

            auth.stop(0);
            assertEquals(2, revocations.minimumVersion("7"), "inside the window the cached list answers, server or no server");
        } finally {
            auth.stop(0);
        }
    }

    @Test
    void anUnreachableOrBrokenFeedFailsOpenRatherThanFailingTheRequest() throws Exception {
        HttpServer down = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        int port = down.getAddress().getPort(); // bound, never started: nothing answers
        down.stop(0);
        assertEquals(0, new Revocations("http://localhost:" + port + "/token-versions").minimumVersion("7"));

        HttpServer broken = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        broken.createContext("/token-versions", exchange -> exchange.sendResponseHeaders(500, -1));
        broken.start();
        try {
            assertEquals(0, new Revocations("http://localhost:" + broken.getAddress().getPort() + "/token-versions")
                    .minimumVersion("7"));
        } finally {
            broken.stop(0);
        }
    }
}
