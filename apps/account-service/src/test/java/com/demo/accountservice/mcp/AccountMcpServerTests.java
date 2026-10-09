package com.demo.accountservice.mcp;

import com.demo.accountservice.account.AccountService;
import com.demo.accountservice.account.NewAccount;
import com.demo.accountservice.token.Caller;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * The MCP face of the accounts, exercised with the real MCP client over real HTTP: the same code path
 * agent-service takes. A throwaway JWKS server stands in for auth-service.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:sqlite:target/mcp-test.db",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.hikari.maximum-pool-size=1",
})
class AccountMcpServerTests {

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

    @Autowired
    AccountService bank;

    @Test
    void onlyTheReadingToolsAreAnnotatedReadOnly() {
        try (McpSyncClient client = client(token("1", null))) {
            client.initialize();
            List<Tool> tools = client.listTools().tools();
            assertEquals(List.of("list_accounts", "list_transfers", "transfer"), tools.stream().map(Tool::name).sorted().toList());
            for (Tool tool : tools) {
                boolean readOnly = tool.annotations() != null && Boolean.TRUE.equals(tool.annotations().readOnlyHint());
                assertEquals(!tool.name().equals("transfer"), readOnly,
                        tool.name() + ": the READ permission in agent-service keys on this flag, so only reading may carry it");
            }
        }
    }

    @Test
    void anAgentTokenReachesTheAgentsAccountsAndNoOthers() {
        Caller owner = new Caller("20", "USER");
        long mine = bank.create(owner, new NewAccount("mine", null)).getId();
        long bots = bank.create(owner, new NewAccount("bot", 5L)).getId();
        bank.deposit(owner, bots, 100L);

        try (McpSyncClient bot = client(token("20", 5L)); McpSyncClient other = client(token("20", 6L))) {
            bot.initialize();
            other.initialize();

            String listed = text(bot.callTool(new CallToolRequest("list_accounts", Map.of())));
            assertTrue(listed.contains("#" + bots + " bot: 100 cents (agent 5)"), listed);
            assertFalse(listed.contains("#" + mine + " "), "the owner's own account is not the agent's");
            assertEquals("no accounts", text(other.callTool(new CallToolRequest("list_accounts", Map.of()))));

            CallToolResult moved = bot.callTool(new CallToolRequest("transfer", Map.of("from", bots, "to", mine, "amount", 40)));
            assertFalse(Boolean.TRUE.equals(moved.isError()), text(moved));
            assertTrue(text(moved).endsWith("#" + bots + " -> #" + mine + " 40 (agent 5)"), text(moved));
            assertEquals(60, bank.get(owner, bots).getBalance());

            CallToolResult refused = bot.callTool(new CallToolRequest("transfer", Map.of("from", mine, "to", bots, "amount", 1)));
            assertTrue(Boolean.TRUE.equals(refused.isError()), "an agent must not spend from an account it does not own");
            assertEquals("no such account", text(refused));

            assertTrue(Boolean.TRUE.equals(client(null).callTool(new CallToolRequest("list_accounts", Map.of())).isError()));

            CallToolResult fraction = bot.callTool(new CallToolRequest("transfer", Map.of("from", bots, "to", mine, "amount", 40.9)));
            // The SDK's schema check catches it first ("integer expected"); number() is the backstop should that ever relax.
            assertTrue(Boolean.TRUE.equals(fraction.isError()), "40.9 cents must not quietly become 40");
            assertEquals(60, bank.get(owner, bots).getBalance());
        }
    }

    @Test
    void aLoginTokenIsRefusedByEveryTool() {
        Caller owner = new Caller("30", "USER");
        bank.create(owner, new NewAccount("mine", null));
        // agent-service forwards whatever token the client connected with; the owner's own must not widen an agent's reach.
        try (McpSyncClient asOwner = client(token("30", null))) {
            asOwner.initialize();
            CallToolResult refused = asOwner.callTool(new CallToolRequest("list_accounts", Map.of()));
            assertTrue(Boolean.TRUE.equals(refused.isError()));
            assertEquals("connect with the agent's own token, not a login token", text(refused));
        }
    }

    @Test
    void aFractionalAmountInARestBodyIsAnErrorNotATruncation() throws Exception {
        Caller owner = new Caller("40", "USER");
        long id = bank.create(owner, new NewAccount("mine", null)).getId();
        HttpRequest deposit = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/bank/accounts/" + id + "/deposit"))
                .header("Authorization", "Bearer " + token("40", null)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"amount\":99.99}")).build();
        HttpResponse<String> res = HttpClient.newHttpClient().send(deposit, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, res.statusCode(), res.body());
        assertTrue(res.body().contains("\"error\""), res.body());
        assertEquals(0, bank.get(owner, id).getBalance(), "nothing may have been credited");
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
        McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(10)).build();
        if (token == null) {
            client.initialize();
        }
        return client;
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().get(0)).text();
    }

    /** The owner's login token when {@code agent} is null, else an agent token pinned to that agent. */
    private static String token(String subject, Long agent) {
        try {
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().subject(subject)
                    .claim("role", agent == null ? "USER" : "AGENT")
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)));
            if (agent != null) {
                claims.claim("agent", agent);
            }
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims.build());
            jwt.sign(new RSASSASigner(KEY));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
