package com.demo.walletservice.mcp;

import com.demo.walletservice.wallet.dto.Access;
import com.demo.walletservice.wallet.WalletService;
import com.demo.walletservice.wallet.dto.NewWallet;
import com.demo.auth.client.Caller;
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
import java.math.BigInteger;
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
 * The MCP face of the wallets, exercised with the real MCP client over real HTTP: the same code path
 * agent-service takes. A throwaway JWKS server stands in for auth-service.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:sqlite:target/mcp-test.db",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.hikari.maximum-pool-size=1",
})
class WalletMcpServerTests {

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
    WalletService service;

    @Test
    void onlyTheReadingToolsAreAnnotatedReadOnly() {
        try (McpSyncClient client = client(token("1", null))) {
            client.initialize();
            List<Tool> tools = client.listTools().tools();
            assertEquals(List.of("list_transfers", "list_wallets", "transfer"), tools.stream().map(Tool::name).sorted().toList());
            for (Tool tool : tools) {
                boolean readOnly = tool.annotations() != null && Boolean.TRUE.equals(tool.annotations().readOnlyHint());
                assertEquals(!tool.name().equals("transfer"), readOnly,
                        tool.name() + ": the READ grant in agent-service keys on this flag, so only reading may carry it");
            }
        }
    }

    @Test
    void anAgentTokenReachesTheAgentsWalletsAndNoOthers() {
        Caller owner = new Caller("20", "USER");
        long mine = service.create(owner, new NewWallet("mine", null)).getId();
        long bots = service.create(owner, new NewWallet("bot", 5L)).getId();
        service.deposit(owner, bots, 100L);

        try (McpSyncClient bot = client(token("20", 5L)); McpSyncClient other = client(token("20", 6L))) {
            bot.initialize();
            other.initialize();

            String listed = text(bot.callTool(new CallToolRequest("list_wallets", Map.of())));
            assertTrue(listed.contains("#" + bots + " bot: 100 cents (agent 5)"), listed);
            assertFalse(listed.contains("#" + mine + " "), "the owner's own wallet is not the agent's");
            assertEquals("no wallets", text(other.callTool(new CallToolRequest("list_wallets", Map.of()))));

            CallToolResult moved = bot.callTool(new CallToolRequest("transfer", Map.of("from", bots, "to", mine, "amount", 40)));
            assertFalse(Boolean.TRUE.equals(moved.isError()), text(moved));
            assertTrue(text(moved).endsWith("#" + bots + " -> #" + mine + " 40 (agent 5)"), text(moved));
            assertEquals(60, service.get(owner, bots).getBalance());

            CallToolResult refused = bot.callTool(new CallToolRequest("transfer", Map.of("from", mine, "to", bots, "amount", 1)));
            assertTrue(Boolean.TRUE.equals(refused.isError()), "an agent must not spend from a wallet it does not own");
            assertEquals("no such wallet", text(refused));

            try (McpSyncClient anonymous = client(null)) {
                assertTrue(Boolean.TRUE.equals(anonymous.callTool(new CallToolRequest("list_wallets", Map.of())).isError()));
            }

            CallToolResult fraction = bot.callTool(new CallToolRequest("transfer", Map.of("from", bots, "to", mine, "amount", 40.9)));
            // The SDK's schema check catches it first ("integer expected"); number() is the backstop should that ever relax.
            assertTrue(Boolean.TRUE.equals(fraction.isError()), "40.9 cents must not quietly become 40");
            // Past the long range Jackson hands over a BigInteger; its low 64 bits are wallet #mine and 40 cents.
            BigInteger wrap = BigInteger.ONE.shiftLeft(64);
            CallToolResult wrapped = bot.callTool(new CallToolRequest("transfer",
                    Map.of("from", bots, "to", wrap.add(BigInteger.valueOf(mine)), "amount", wrap.add(BigInteger.valueOf(40)))));
            assertTrue(Boolean.TRUE.equals(wrapped.isError()), "2^64+n must not quietly become n");
            assertEquals("to must be a whole number", text(wrapped));
            assertEquals(60, service.get(owner, bots).getBalance());
        }
    }

    @Test
    void aLoginTokenIsRefusedByEveryTool() {
        Caller owner = new Caller("30", "USER");
        long mine = service.create(owner, new NewWallet("mine", null)).getId();
        long other = service.create(owner, new NewWallet("other", null)).getId();
        service.deposit(owner, mine, 100L);
        // agent-service forwards whatever token the client connected with; the owner's own must not widen an agent's reach.
        try (McpSyncClient asOwner = client(token("30", null))) {
            asOwner.initialize();
            for (CallToolRequest call : List.of(
                    new CallToolRequest("list_wallets", Map.of()),
                    new CallToolRequest("list_transfers", Map.of("wallet", mine)),
                    new CallToolRequest("transfer", Map.of("from", mine, "to", other, "amount", 10)))) {
                CallToolResult refused = asOwner.callTool(call);
                assertTrue(Boolean.TRUE.equals(refused.isError()), call.name());
                assertEquals("connect with the agent's own token, not a login token", text(refused), call.name());
            }
        }
        assertEquals(100, service.get(owner, mine).getBalance(), "nothing may have moved");
    }

    @Test
    void aGrantDecidesWhatAnAgentMayDoOverMcp() {
        Caller owner = new Caller("50", "USER");
        long shared = service.create(owner, new NewWallet("household", null)).getId();
        long shop = service.create(new Caller("51", "USER"), new NewWallet("shop", null)).getId();
        service.deposit(owner, shared, 300L);

        try (McpSyncClient helper = client(token("52", 9L))) {
            helper.initialize();
            CallToolRequest pay = new CallToolRequest("transfer", Map.of("from", shared, "to", shop, "amount", 25));

            assertEquals("no wallets", text(helper.callTool(new CallToolRequest("list_wallets", Map.of()))), "no grant, nothing listed");
            assertEquals("no such wallet", text(helper.callTool(new CallToolRequest("list_transfers", Map.of("wallet", shared)))));

            service.setGrant(owner, shared, 9L, Access.READ);
            assertTrue(text(helper.callTool(new CallToolRequest("list_wallets", Map.of()))).contains("#" + shared + " household: 300 cents"));
            assertTrue(text(helper.callTool(new CallToolRequest("list_transfers", Map.of("wallet", shared)))).contains("-> #" + shared + " 300 (user 50)"),
                    "READ sees the history");
            CallToolResult refused = helper.callTool(pay);
            assertTrue(Boolean.TRUE.equals(refused.isError()), "READ does not spend");
            assertEquals("no such wallet", text(refused));

            service.setGrant(owner, shared, 9L, Access.WRITE);
            CallToolResult paid = helper.callTool(pay);
            assertFalse(Boolean.TRUE.equals(paid.isError()), text(paid));
            assertTrue(text(paid).endsWith("#" + shared + " -> #" + shop + " 25 (agent 9)"), text(paid));
            CallToolResult broke = helper.callTool(new CallToolRequest("transfer", Map.of("from", shared, "to", shop, "amount", 1_000)));
            assertEquals("insufficient funds", text(broke));

            service.removeGrant(owner, shared, 9L);
            assertEquals("no wallets", text(helper.callTool(new CallToolRequest("list_wallets", Map.of()))), "a removed grant bites at once");
        }
        assertEquals(275, service.get(owner, shared).getBalance());
        assertEquals(25, service.get(new Caller("51", "USER"), shop).getBalance());
    }

    @Test
    void aFractionalAmountInARestBodyIsAnErrorNotATruncation() throws Exception {
        Caller owner = new Caller("40", "USER");
        long id = service.create(owner, new NewWallet("mine", null)).getId();
        HttpRequest deposit = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/wallets/" + id + "/deposit"))
                .header("Authorization", "Bearer " + token("40", null)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"amount\":99.99}")).build();
        HttpResponse<String> res = HttpClient.newHttpClient().send(deposit, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, res.statusCode(), res.body());
        assertTrue(res.body().contains("\"error\""), res.body());
        assertEquals(0, service.get(owner, id).getBalance(), "nothing may have been credited");
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
                claims.claim("agent", agent).claim("agentVer", 0);
            }
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims.build());
            jwt.sign(new RSASSASigner(KEY));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
