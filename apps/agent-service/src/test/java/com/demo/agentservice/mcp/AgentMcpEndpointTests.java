package com.demo.agentservice.mcp;

import com.demo.agentservice.activity.Activity;
import com.demo.agentservice.activity.ActivityLog;
import com.demo.agentservice.agent.entities.Access;
import com.demo.agentservice.agent.entities.Agent;
import com.demo.agentservice.agent.AgentService;
import com.demo.agentservice.agent.dto.NewAgent;
import com.demo.agentservice.agent.dto.NewMcpServer;
import com.demo.agentservice.agent.entities.OthersAccess;
import com.demo.agentservice.agent.ServerUrls;
import com.demo.agentservice.agent.dto.UpdateAgent;
import com.demo.agentservice.agent.dto.UpdateMcpServer;
import com.demo.auth.client.Caller;
import com.demo.auth.client.JwtVerifier;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ImageContent;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The permission rules, end to end: the real MCP client connects to {@code /mcp?agent=} as an external agent
 * would, a real MCP server (the SDK's own, mounted in this very context) stands in for an attached server,
 * and the log says what was allowed and what was refused.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:sqlite:target/mcp-test.db",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.datasource.hikari.maximum-pool-size=1",
        // The fake server is on loopback. Under this exact URL the deployment trusts it, annotations and all; under any
        // other (a query string will do) it is an untrusted server, which the resolver below lets through the IP check.
        // The port is only known once the server is up; ServerUrls reads this on every call, so the placeholder is fine.
        "agents.trusted-server-urls=http://127.0.0.1:${local.server.port}/fake-mcp",
})
class AgentMcpEndpointTests {

    /** Every tool call the fake server received, by tool name. */
    static final List<String> CALLS = new CopyOnWriteArrayList<>();

    /** While true, 127.0.0.1 is reported as a public address, so the loopback fake server can stand in for an untrusted public one. */
    static volatile boolean loopbackLooksPublic = true;

    @TestConfiguration
    static class FakeMcp {
        @Bean
        @Primary
        ServerUrls serverUrls(Environment env) {
            return new ServerUrls(env, host -> new InetAddress[] {
                    InetAddress.getByName(loopbackLooksPublic && host.equals("127.0.0.1") ? "93.184.216.34" : host)});
        }

        @Bean
        ServletRegistrationBean<HttpServletStatelessServerTransport> fakeMcp() {
            HttpServletStatelessServerTransport transport = HttpServletStatelessServerTransport.builder()
                    .messageEndpoint("/fake-mcp")
                    .contextExtractor(request -> McpTransportContext.create(
                            Map.of("authorization", String.valueOf(request.getHeader("Authorization")))))
                    .build();
            McpServer.sync(transport).serverInfo("fake", "0").capabilities(ServerCapabilities.builder().tools(false).build())
                    .tools(tool("read_thing", ToolAnnotations.builder().readOnlyHint(true).build()),
                            tool("write_thing", null),
                            picture())
                    .build();
            ServletRegistrationBean<HttpServletStatelessServerTransport> servlet = new ServletRegistrationBean<>(transport, "/fake-mcp");
            servlet.setName("fake-mcp");
            servlet.setAsyncSupported(true);
            return servlet;
        }

        /** A tool whose result is not text: a one-pixel PNG plus structured content, as a real server might answer. */
        static final String PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

        private static SyncToolSpecification picture() {
            Tool tool = Tool.builder().name("picture_thing").description("a picture").inputSchema(Map.of("type", "object")).build();
            return SyncToolSpecification.builder().tool(tool).callHandler((context, request) -> {
                CALLS.add("picture_thing");
                return CallToolResult.builder().addTextContent("here you go").addContent(new ImageContent(null, PNG, "image/png"))
                        .structuredContent(Map.of("width", 1, "height", 1)).build();
            }).build();
        }

        private static SyncToolSpecification tool(String name, ToolAnnotations annotations) {
            Tool.Builder tool = Tool.builder().name(name).description(name)
                    .inputSchema(Map.of("type", "object", "properties", Map.of("x", Map.of("type", "string"))));
            if (annotations != null) {
                tool.annotations(annotations);
            }
            return SyncToolSpecification.builder().tool(tool.build()).callHandler((context, request) -> {
                CALLS.add(name);
                return CallToolResult.builder().addTextContent(name + " saw " + context.get("authorization")).build();
            }).build();
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    AgentService agents;

    @Autowired
    ActivityLog activity;

    /** Unit-tested against a real JWKS elsewhere; here three fixed bearers stand for three kinds of caller. */
    @MockitoBean
    JwtVerifier jwt;

    static final Caller OWNER = new Caller("100", "USER");
    static final Caller STRANGER = new Caller("101", "USER");

    /** The agent id an "agent token" bearer is pinned to; set per test. */
    static volatile Long pinned;

    @BeforeEach
    void setUp() {
        CALLS.clear();
        loopbackLooksPublic = true;
        when(jwt.callerOf(any())).thenAnswer(call -> switch (String.valueOf((Object) call.getArgument(0))) {
            case "Bearer tok-100" -> OWNER;
            case "Bearer tok-101" -> STRANGER;
            case "Bearer agent-tok" -> new Caller("100", "AGENT", pinned);
            default -> throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid or expired token");
        });
    }

    /** An agent whose only server is the fake one, trusted by the deployment, with the given access. */
    private Agent agentWith(Access access, OthersAccess others) {
        return agentWith(access, others, "/fake-mcp", null);
    }

    /** The same server reached by a URL the deployment does not trust: an owner's own server, as far as the gateway knows, so it is not sent the caller's token. */
    private Agent agentWithUntrusted(Access access, List<String> readOnlyTools) {
        return agentWith(access, OthersAccess.NONE, "/fake-mcp?untrusted", readOnlyTools);
    }

    private Agent agentWith(Access access, OthersAccess others, String endpoint, List<String> readOnlyTools) {
        Agent agent = agents.create(OWNER, new NewAgent("gateway", "Be brief.", others));
        agents.removeServer(OWNER, agent.getId(), agent.getServers().get(0).getId(), "");
        boolean trusted = endpoint.equals("/fake-mcp");
        agents.addServer(OWNER, agent.getId(), new NewMcpServer("fake", "http://127.0.0.1:" + port + endpoint, null, trusted, access, readOnlyTools), "");
        return agents.get(OWNER, agent.getId());
    }

    private McpSyncClient connect(String bearer, Long agentId) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                .endpoint(agentId == null ? "/mcp" : "/mcp?agent=" + agentId)
                .httpRequestCustomizer((builder, method, uri, body, context) -> {
                    if (bearer != null) {
                        builder.header("Authorization", "Bearer " + bearer);
                    }
                })
                .build();
        McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(10)).build();
        client.initialize();
        return client;
    }

    private static List<String> names(McpSyncClient client) {
        return client.listTools().tools().stream().map(Tool::name).sorted().toList();
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().get(0)).text();
    }

    private List<String> log(Long agentId, String kind) {
        return activity.recent(agentId).stream().filter(a -> a.getKind().equals(kind)).map(Activity::getDetail).toList();
    }

    @Test
    void readOffersOnlyReadOnlyToolsAndRefusesTheRest() {
        Agent agent = agentWith(Access.READ, OthersAccess.NONE);
        try (McpSyncClient client = connect("tok-100", agent.getId())) {
            assertEquals(List.of("fake__read_thing"), names(client), "a READ server's writing tools are not even described");

            CallToolResult refused = client.callTool(new CallToolRequest("fake__write_thing", Map.of("x", "1")));
            assertTrue(Boolean.TRUE.equals(refused.isError()));
            assertEquals("denied: needs WRITE on server 'fake' (has READ)", text(refused));
            assertEquals(List.of(), CALLS, "the refused call never reached the server");

            CallToolResult ok = client.callTool(new CallToolRequest("fake__read_thing", Map.of("x", "2")));
            assertFalse(Boolean.TRUE.equals(ok.isError()));
            assertEquals("read_thing saw Bearer tok-100", text(ok), "the connecting token is what the server sees");
            assertEquals(List.of("read_thing"), CALLS);

            CallToolResult unknown = client.callTool(new CallToolRequest("nothing__here", Map.of()));
            assertEquals("denied: no such tool: nothing__here", text(unknown));
        }
        assertEquals(List.of("nothing__here: no such tool: nothing__here", "fake__write_thing: needs WRITE on server 'fake' (has READ)"),
                log(agent.getId(), Activity.TOOL_DENIED));
        assertEquals(List.of("fake__read_thing {x=2} -> ok: read_thing saw Bearer tok-100"), log(agent.getId(), Activity.TOOL_CALL));
    }

    @Test
    void writeOffersEverythingAndAChangeBetweenCallsBitesOnTheNext() {
        Agent agent = agentWith(Access.WRITE, OthersAccess.NONE);
        Long server = agent.getServers().get(0).getId();
        try (McpSyncClient client = connect("tok-100", agent.getId())) {
            assertEquals(List.of("fake__picture_thing", "fake__read_thing", "fake__write_thing"), names(client));
            assertFalse(Boolean.TRUE.equals(client.callTool(new CallToolRequest("fake__write_thing", Map.of())).isError()));

            // The owner flips the server to READ while the agent is connected.
            agents.updateServer(OWNER, agent.getId(), server, new UpdateMcpServer(null, null, null, null, Access.READ), "");

            assertTrue(Boolean.TRUE.equals(client.callTool(new CallToolRequest("fake__write_thing", Map.of())).isError()));
            assertEquals(List.of("fake__read_thing"), names(client), "the list obeys the change too");
        }
        assertEquals(List.of("write_thing"), CALLS, "the first call ran, the second was refused");
        assertEquals(List.of("fake__write_thing: needs WRITE on server 'fake' (has READ)"), log(agent.getId(), Activity.TOOL_DENIED));
    }

    @Test
    void othersAccessGatesTheAgentToolsAndNeverTheAgentItself() {
        Agent sibling = agents.create(OWNER, new NewAgent("sibling", "", OthersAccess.NONE));
        Long siblingTodos = sibling.getServers().get(0).getId();
        Agent stranger = agents.create(STRANGER, new NewAgent("not yours", "", OthersAccess.WRITE));
        Agent agent = agentWith(Access.READ, OthersAccess.NONE);
        Long ownServer = agent.getServers().get(0).getId();

        try (McpSyncClient client = connect("tok-100", agent.getId())) {
            // NONE: nothing offered, and a call anyway is refused.
            assertEquals(List.of("fake__read_thing"), names(client));
            assertEquals("denied: needs othersAccess READ (has NONE)", text(client.callTool(new CallToolRequest("list_agents", Map.of()))));

            // READ: reading tools work and only the owner's agents show; writing is refused.
            agents.update(OWNER, agent.getId(), new UpdateAgent(null, null, OthersAccess.READ), "");
            assertEquals(List.of("fake__read_thing", "get_agent", "get_agent_activity", "list_agents"), names(client));
            String listed = text(client.callTool(new CallToolRequest("list_agents", Map.of())));
            assertTrue(listed.contains("#" + sibling.getId() + " sibling (others: NONE, 1 servers)"), listed);
            assertFalse(listed.contains("#" + agent.getId() + " "), "the agent does not list itself: " + listed);
            assertFalse(listed.contains("not yours"), "another user's agent must never show");
            CallToolResult theirs = client.callTool(new CallToolRequest("get_agent", Map.of("id", stranger.getId())));
            assertTrue(Boolean.TRUE.equals(theirs.isError()));
            assertEquals("no such agent", text(theirs));
            assertEquals("denied: needs othersAccess WRITE (has READ)",
                    text(client.callTool(new CallToolRequest("update_agent", Map.of("id", sibling.getId(), "name", "renamed")))));
            CallToolResult malformed = client.callTool(new CallToolRequest("get_agent", Map.of("id", "seven")));
            assertTrue(Boolean.TRUE.equals(malformed.isError()));
            assertEquals("id must be a whole number", text(malformed), "a bad argument is the model's mistake, not a refusal");
            assertEquals("sibling", agents.get(OWNER, sibling.getId()).getName());

            // WRITE: the sibling can be changed, and both logs say so; the agent itself stays out of reach.
            agents.update(OWNER, agent.getId(), new UpdateAgent(null, null, OthersAccess.WRITE), "");
            assertEquals("server 'todos' is now WRITE", text(client.callTool(new CallToolRequest("set_mcp_access",
                    Map.of("id", sibling.getId(), "serverId", siblingTodos, "access", "WRITE")))));
            assertEquals("denied: an agent cannot change its own configuration", text(client.callTool(new CallToolRequest("set_mcp_access",
                    Map.of("id", agent.getId(), "serverId", ownServer, "access", "WRITE")))));
            assertEquals("denied: an agent cannot change its own configuration", text(client.callTool(new CallToolRequest("update_agent",
                    Map.of("id", agent.getId(), "othersAccess", "WRITE")))));
        }
        assertEquals(Access.WRITE, agents.get(OWNER, sibling.getId()).getServers().get(0).getAccess());
        assertEquals("by agent 'gateway' (#" + agent.getId() + "): server 'todos' access READ -> WRITE",
                log(sibling.getId(), Activity.CONFIG_CHANGED).get(0));
        assertEquals(Access.READ, agents.get(OWNER, agent.getId()).getServers().get(0).getAccess(), "it must not widen its own access");
        List<String> denied = log(agent.getId(), Activity.TOOL_DENIED);
        assertEquals("update_agent: an agent cannot change its own configuration", denied.get(0));
        assertEquals("set_mcp_access: an agent cannot change its own configuration", denied.get(1));
        assertTrue(denied.stream().noneMatch(d -> d.contains("must be a whole number")), "malformed arguments are not refusals: " + denied);
    }

    @Test
    void whoMayConnect() {
        Agent agent = agentWith(Access.READ, OthersAccess.NONE);
        Agent other = agents.create(OWNER, new NewAgent("other", "", OthersAccess.NONE));

        // The owner's login token, and an agent token pinned to this very agent.
        pinned = agent.getId();
        try (McpSyncClient client = connect("agent-tok", agent.getId())) {
            assertEquals(List.of("fake__read_thing"), names(client));
            assertEquals("read_thing saw Bearer agent-tok", text(client.callTool(new CallToolRequest("fake__read_thing", Map.of()))),
                    "the agent token is what gets forwarded, so a downstream server sees the owner");
            // The 30-day token buys no more than the row allows: READ lists, and a write is refused before it is forwarded.
            CallToolResult refused = client.callTool(new CallToolRequest("fake__write_thing", Map.of()));
            assertTrue(Boolean.TRUE.equals(refused.isError()));
            assertEquals("denied: needs WRITE on server 'fake' (has READ)", text(refused));
            assertEquals(List.of("read_thing"), CALLS);
        }

        // The agent token names its agent, so plain /mcp works with it.
        try (McpSyncClient client = connect("agent-tok", null)) {
            assertEquals(List.of("fake__read_thing"), names(client));
        }

        // An agent token for another agent, another user, no token, a login token with no agent id: each refused, nothing learned.
        pinned = other.getId();
        assertRefused(() -> connect("agent-tok", agent.getId()), "no such agent");
        assertRefused(() -> connect("tok-101", agent.getId()), "no such agent");
        assertRefused(() -> connect(null, agent.getId()), "invalid or expired token");
        assertRefused(() -> connect("tok-100", null), "which agent? connect to /mcp?agent=<id>");
        assertEquals(2, log(agent.getId(), Activity.CONNECTED).size(), "only the two accepted connections are in the log");

        // A stored header, rather than the caller's token: encrypted at rest, and what the server actually receives.
        pinned = agent.getId();
        agents.updateServer(OWNER, agent.getId(), agent.getServers().get(0).getId(),
                new UpdateMcpServer(null, null, "Bearer stored-s3cret", false, null), "");
        try (McpSyncClient client = connect("agent-tok", null)) {
            assertEquals("read_thing saw Bearer stored-s3cret", text(client.callTool(new CallToolRequest("fake__read_thing", Map.of()))));
        }
    }

    /** The gateway passes a result through whole: an image is still an image on the other side, and structured content survives. */
    @Test
    void imagesAndStructuredContentComeThroughTheGatewayUnchanged() {
        Agent agent = agentWith(Access.WRITE, OthersAccess.NONE);
        try (McpSyncClient client = connect("tok-100", agent.getId())) {
            CallToolResult result = client.callTool(new CallToolRequest("fake__picture_thing", Map.of()));
            assertFalse(Boolean.TRUE.equals(result.isError()));
            assertEquals(2, result.content().size(), result.content().toString());
            assertEquals("here you go", text(result));
            ImageContent image = (ImageContent) result.content().get(1);
            assertEquals("image/png", image.mimeType());
            assertEquals(FakeMcp.PNG, image.data(), "the bytes are the server's, not a toString of them");
            assertEquals(Map.of("width", 1, "height", 1), result.structuredContent());
        }
        String logged = log(agent.getId(), Activity.TOOL_CALL).get(0);
        assertTrue(logged.startsWith("fake__picture_thing {} -> ok: here you go [image image/png] [structured {"), logged);
        assertTrue(logged.contains("width=1") && logged.contains("height=1") && !logged.contains(FakeMcp.PNG), "a summary, never the bytes: " + logged);
    }

    /** The URL policy runs again at connect time, against what the name resolves to then, not only when the row was saved. */
    @Test
    void aServerPublicWhenSavedButPrivateWhenConnectingIsRefusedAtConnect() {
        Agent agent = agentWithUntrusted(Access.WRITE, List.of("read_thing"));
        try (McpSyncClient client = connect("tok-100", agent.getId())) {
            assertEquals(List.of("fake__picture_thing", "fake__read_thing", "fake__write_thing"), names(client), "public: reachable");
            // Between two calls the name starts resolving to the loopback address it really is: DNS rebinding.
            loopbackLooksPublic = false;
            assertEquals(List.of(), names(client), "the listing refuses it before connecting");
            CallToolResult refused = client.callTool(new CallToolRequest("fake__read_thing", Map.of()));
            assertTrue(Boolean.TRUE.equals(refused.isError()));
            assertEquals("error: the tool call failed; the agent's activity log has the detail", text(refused));
            loopbackLooksPublic = true;
            assertEquals(List.of("fake__picture_thing", "fake__read_thing", "fake__write_thing"), names(client), "public again: reachable again");
        }
        assertEquals(List.of(), CALLS, "nothing reached the server while it was refused");
        List<String> calls = log(agent.getId(), Activity.TOOL_CALL);
        assertTrue(calls.get(0).startsWith("fake__read_thing {} -> error: refused: '127.0.0.1' resolves to 127.0.0.1"), calls.toString());
        assertTrue(calls.get(1).startsWith("server 'fake': could not connect: refused: '127.0.0.1' resolves to 127.0.0.1"), calls.toString());
    }

    /** A server the owner typed in can annotate anything, so on it READ is the owner's list and the annotation is ignored. */
    @Test
    void onAnUntrustedServerReadOffersOnlyWhatTheOwnerMarkedReadOnly() {
        // The owner marks write_thing, not read_thing: perverse, and exactly what proves whose word counts.
        Agent agent = agentWithUntrusted(Access.READ, List.of("write_thing"));
        Long server = agent.getServers().get(0).getId();
        try (McpSyncClient client = connect("tok-100", agent.getId())) {
            assertEquals(List.of("fake__write_thing"), names(client), "the server's readOnlyHint on read_thing is not trusted");
            CallToolResult refused = client.callTool(new CallToolRequest("fake__read_thing", Map.of()));
            assertTrue(Boolean.TRUE.equals(refused.isError()));
            assertEquals("denied: needs WRITE on server 'fake' (has READ)", text(refused));
            assertFalse(Boolean.TRUE.equals(client.callTool(new CallToolRequest("fake__write_thing", Map.of())).isError()));

            // The owner clears the list: READ on an untrusted server then offers nothing at all.
            agents.updateServer(OWNER, agent.getId(), server, new UpdateMcpServer(null, null, null, null, null, List.of()), "");
            assertEquals(List.of(), names(client));
            assertTrue(Boolean.TRUE.equals(client.callTool(new CallToolRequest("fake__write_thing", Map.of())).isError()));

            // WRITE offers everything, whatever the list says.
            agents.updateServer(OWNER, agent.getId(), server, new UpdateMcpServer(null, null, null, null, Access.WRITE, null), "");
            assertEquals(List.of("fake__picture_thing", "fake__read_thing", "fake__write_thing"), names(client));
        }
        assertEquals(List.of("write_thing"), CALLS);
        assertEquals("server 'fake' read-only tools cleared", log(agent.getId(), Activity.CONFIG_CHANGED).get(1));
        assertEquals(List.of("fake__write_thing: needs WRITE on server 'fake' (has READ)", "fake__read_thing: needs WRITE on server 'fake' (has READ)"),
                log(agent.getId(), Activity.TOOL_DENIED));
    }

    /** The client wraps the server's JSON-RPC error a couple of times; the reason is somewhere down the cause chain. */
    private static void assertRefused(Runnable connect, String why) {
        RuntimeException e = assertThrows(RuntimeException.class, connect::run);
        StringBuilder all = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            all.append(t.getMessage()).append(" | ");
        }
        assertTrue(all.toString().contains(why), "expected '" + why + "' in: " + all);
    }

    @Test
    void initializeCarriesTheInstructionsAndADeadServerIsSkippedNotFatal() {
        Agent agent = agents.create(OWNER, new NewAgent("lonely", "Only ever list.", OthersAccess.NONE));
        agents.updateServer(OWNER, agent.getId(), agent.getServers().get(0).getId(),
                // Off the trusted list it may no longer forward the token, so that is switched off in the same edit.
                new UpdateMcpServer(null, "http://127.0.0.1:1/nothing", null, false, null), "");
        try (McpSyncClient client = connect("tok-100", agent.getId())) {
            InitializeResult hello = client.getCurrentInitializationResult();
            assertEquals("Only ever list.", hello.instructions());
            assertEquals("agent-service: lonely", hello.serverInfo().name());
            assertEquals(List.of(), names(client));
            assertEquals(List.of(), names(client), "listed again, as clients do");
        }
        List<String> calls = log(agent.getId(), Activity.TOOL_CALL);
        assertEquals(1, calls.size(), "one line for the dead server, however often the tools are listed: " + calls);
        assertTrue(calls.get(0).startsWith("server 'todos': could not connect"));
        assertTrue(log(agent.getId(), Activity.CONNECTED).get(0).startsWith("Java SDK MCP Client"), log(agent.getId(), Activity.CONNECTED).toString());

        Agent quiet = agents.create(OWNER, new NewAgent("quiet", "", OthersAccess.NONE));
        try (McpSyncClient client = connect("tok-100", quiet.getId())) {
            assertNull(client.getCurrentInitializationResult().instructions(), "no instructions means none, not an empty string");
        }
    }
}
