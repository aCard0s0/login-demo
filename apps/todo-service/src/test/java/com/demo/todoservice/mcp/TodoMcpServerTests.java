package com.demo.todoservice.mcp;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The MCP face of the todo list, exercised with the real MCP client over real HTTP: the same code path
 * agent-service takes. A throwaway JWKS server stands in for auth-service, as in JwtVerifierTests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:sqlite:target/mcp-test.db",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.hikari.maximum-pool-size=1",
})
class TodoMcpServerTests {

    static final RSAKey KEY;
    static final HttpServer JWKS;

    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("test").generate();
            JWKS = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            Map.of("/jwks.json", new JWKSet(KEY.toPublicJWK()).toString(), "/token-versions", "{}")
                    .forEach((path, json) -> JWKS.createContext(path, exchange -> {
                        byte[] body = json.getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().add("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, body.length);
                        try (OutputStream out = exchange.getResponseBody()) {
                            out.write(body);
                        }
                    }));
            JWKS.start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void authService(DynamicPropertyRegistry registry) {
        String base = "http://localhost:" + JWKS.getAddress().getPort();
        registry.add("auth.jwks-uri", () -> base + "/jwks.json");
        registry.add("auth.token-versions-uri", () -> base + "/token-versions");
    }

    @LocalServerPort
    int port;

    @Test
    void onlyListingIsAnnotatedReadOnly() {
        try (McpSyncClient client = client(token("1"))) {
            client.initialize();
            List<Tool> tools = client.listTools().tools();
            assertEquals(List.of("add_todo", "delete_todo", "list_todos", "update_todo"),
                    tools.stream().map(Tool::name).sorted().toList());
            for (Tool tool : tools) {
                boolean readOnly = tool.annotations() != null && Boolean.TRUE.equals(tool.annotations().readOnlyHint());
                assertEquals(tool.name().equals("list_todos"), readOnly,
                        tool.name() + ": the READ permission in agent-service keys on this flag, so only the listing may carry it");
            }
        }
    }

    @Test
    void eachCallerSeesAndEditsOnlyItsOwnTodos() {
        try (McpSyncClient one = client(token("10")); McpSyncClient two = client(token("11"))) {
            one.initialize();
            two.initialize();

            String added = text(one.callTool(new CallToolRequest("add_todo", Map.of("title", "buy milk"))));
            assertTrue(added.startsWith("#") && added.endsWith("[ ] buy milk"), added);
            long id = Long.parseLong(added.substring(1, added.indexOf(' ')));

            assertTrue(text(one.callTool(new CallToolRequest("list_todos", Map.of()))).contains("buy milk"));
            assertEquals("no todos", text(two.callTool(new CallToolRequest("list_todos", Map.of()))),
                    "another user's todos must not show up");

            CallToolResult theirs = two.callTool(new CallToolRequest("update_todo", Map.of("id", id, "done", true)));
            assertTrue(Boolean.TRUE.equals(theirs.isError()), "another user must not be able to edit it");

            CallToolResult done = one.callTool(new CallToolRequest("update_todo", Map.of("id", id, "done", true)));
            assertFalse(Boolean.TRUE.equals(done.isError()));
            assertEquals("#" + id + " [x] buy milk", text(done));

            assertEquals("deleted", text(one.callTool(new CallToolRequest("delete_todo", Map.of("id", id)))));
            assertEquals("no todos", text(one.callTool(new CallToolRequest("list_todos", Map.of()))));
        }
    }

    @Test
    void aMissingOrBadTokenIsAnErrorResultNotATodo() {
        try (McpSyncClient none = client(null); McpSyncClient bad = client("not.a.token")) {
            none.initialize();
            bad.initialize();
            CallToolResult refused = none.callTool(new CallToolRequest("list_todos", Map.of()));
            assertTrue(Boolean.TRUE.equals(refused.isError()));
            assertEquals("invalid or expired token", text(refused));
            assertTrue(Boolean.TRUE.equals(bad.callTool(new CallToolRequest("add_todo", Map.of("title", "x"))).isError()));
        }
    }

    @Test
    void theEndpointIsNotUnderApi() {
        assertFalse("/mcp".startsWith("/api/"), "the web proxy forwards only /api; /mcp must stay out of the browser's reach");
    }

    private McpSyncClient client(String token) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                .endpoint("/mcp")
                .httpRequestCustomizer((builder, method, uri, body, context) -> {
                    if (token != null) {
                        builder.header("Authorization", "Bearer " + token);
                    }
                })
                .build();
        return McpClient.sync(transport).requestTimeout(Duration.ofSeconds(10)).build();
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().get(0)).text();
    }

    private static String token(String subject) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder().subject(subject).claim("role", "USER")
                    .expirationTime(Date.from(Instant.now().plusSeconds(300))).build();
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(KEY));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
