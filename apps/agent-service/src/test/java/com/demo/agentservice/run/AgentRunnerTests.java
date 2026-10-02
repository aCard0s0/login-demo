package com.demo.agentservice.run;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.DirectCaller;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.ToolUnion;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.Usage;
import com.demo.agentservice.activity.Activity;
import com.demo.agentservice.activity.ActivityLog;
import com.demo.agentservice.agent.Access;
import com.demo.agentservice.agent.Agent;
import com.demo.agentservice.agent.AgentService;
import com.demo.agentservice.agent.NewAgent;
import com.demo.agentservice.agent.NewMcpServer;
import com.demo.agentservice.agent.OthersAccess;
import com.demo.agentservice.agent.UpdateAgent;
import com.demo.agentservice.agent.UpdateMcpServer;
import com.demo.agentservice.token.Caller;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The permission rules, end to end: a scripted model asks for tools, a real MCP server (the SDK's own, mounted
 * in this very context) answers, and the log says what was allowed and what was refused.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:sqlite:target/run-test.db",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.hikari.maximum-pool-size=1",
        // Any value: the model is mocked, but a blank key would make the runner answer 503 before asking it.
        "anthropic.api-key=test",
        "agents.max-turns=4",
})
class AgentRunnerTests {

    /** Every tool call the fake server received, by tool name. */
    static final List<String> CALLS = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class FakeMcp {
        @Bean
        ServletRegistrationBean<HttpServletStatelessServerTransport> fakeMcp() {
            HttpServletStatelessServerTransport transport = HttpServletStatelessServerTransport.builder()
                    .messageEndpoint("/fake-mcp")
                    .contextExtractor(request -> McpTransportContext.create(
                            Map.of("authorization", String.valueOf(request.getHeader("Authorization")))))
                    .build();
            McpServer.sync(transport).serverInfo("fake", "0").capabilities(ServerCapabilities.builder().tools(false).build())
                    .tools(tool("read_thing", ToolAnnotations.builder().readOnlyHint(true).build()),
                            tool("write_thing", null))
                    .build();
            ServletRegistrationBean<HttpServletStatelessServerTransport> servlet = new ServletRegistrationBean<>(transport, "/fake-mcp");
            servlet.setName("fake-mcp");
            servlet.setAsyncSupported(true);
            return servlet;
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
    AgentRunner runner;

    @Autowired
    AgentService agents;

    @Autowired
    ActivityLog activity;

    @MockitoBean
    Model model;

    static final Caller OWNER = new Caller("100", "USER");

    @BeforeEach
    void clear() {
        CALLS.clear();
    }

    /** An agent whose only server is the fake one, with the given access. */
    private Agent agentWith(Access access, OthersAccess others) {
        Agent agent = agents.create(OWNER, new NewAgent("runner", "", others));
        agents.removeServer(OWNER, agent.getId(), agent.getServers().get(0).getId(), "");
        agents.addServer(OWNER, agent.getId(), new NewMcpServer("fake", "http://localhost:" + port + "/fake-mcp", null, true, access), "");
        return agents.get(OWNER, agent.getId());
    }

    private List<String> offered(MessageCreateParams params) {
        return params.tools().orElse(List.of()).stream().map(t -> t.tool().map(com.anthropic.models.messages.Tool::name).orElse("?")).sorted().toList();
    }

    private List<String> log(Long agentId, String kind) {
        return activity.recent(agentId).stream().filter(a -> a.getKind().equals(kind)).map(Activity::getDetail).toList();
    }

    @Test
    void readOffersOnlyReadOnlyToolsAndRefusesTheRest() {
        Agent agent = agentWith(Access.READ, OthersAccess.NONE);
        when(model.create(any())).thenReturn(
                toolUse("t1", "fake__write_thing", Map.of("x", "1")),
                toolUse("t2", "fake__read_thing", Map.of("x", "2")),
                endTurn("done"));

        RunResponse out = runner.run(OWNER, "tok-100", agent.getId(), "do things");

        ArgumentCaptor<MessageCreateParams> sent = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(model, org.mockito.Mockito.times(3)).create(sent.capture());
        assertEquals(List.of("fake__read_thing"), offered(sent.getAllValues().get(0)),
                "a READ server's writing tools are not even described to the model");
        assertEquals(List.of(), CALLS.stream().filter("write_thing"::equals).toList(), "the refused call never reached the server");
        assertEquals(List.of("read_thing"), CALLS);
        assertEquals("done", out.reply());
        assertEquals(3, out.turns());
        assertEquals(List.of("fake__write_thing: no such tool: fake__write_thing"), log(agent.getId(), Activity.TOOL_DENIED));
        assertEquals(List.of("fake__read_thing {x=2} -> ok: read_thing saw Bearer tok-100"), log(agent.getId(), Activity.TOOL_CALL));
        assertTrue(log(agent.getId(), Activity.RUN_FINISHED).get(0).startsWith("3 turns; reply: done"));
    }

    @Test
    void writeOffersEverythingAndAChangeMidRunBitesOnTheNextCall() {
        Agent agent = agentWith(Access.WRITE, OthersAccess.NONE);
        Long server = agent.getServers().get(0).getId();
        List<MessageCreateParams> sent = new ArrayList<>();
        when(model.create(any())).thenAnswer(call -> {
            sent.add(call.getArgument(0));
            return switch (sent.size()) {
                case 1 -> toolUse("t1", "fake__write_thing", Map.of());
                case 2 -> {
                    // The owner flips the server to READ while the run is going.
                    agents.updateServer(OWNER, agent.getId(), server, new UpdateMcpServer(null, null, null, null, Access.READ), "");
                    yield toolUse("t2", "fake__write_thing", Map.of());
                }
                default -> endTurn("ok");
            };
        });

        runner.run(OWNER, "tok-100", agent.getId(), "write twice");

        assertEquals(List.of("fake__read_thing", "fake__write_thing"), offered(sent.get(0)));
        assertEquals(List.of("write_thing"), CALLS, "the first call ran, the second was refused");
        assertEquals(List.of("fake__write_thing: needs WRITE on server 'fake' (has READ)"), log(agent.getId(), Activity.TOOL_DENIED));
        assertEquals(List.of("fake__write_thing {} -> ok: write_thing saw Bearer tok-100"), log(agent.getId(), Activity.TOOL_CALL));
    }

    @Test
    void othersAccessGatesTheAgentToolsAndNeverTheAgentItself() {
        Agent sibling = agents.create(OWNER, new NewAgent("sibling", "", OthersAccess.NONE));
        Long siblingTodos = sibling.getServers().get(0).getId();
        Agent stranger = agents.create(new Caller("101", "USER"), new NewAgent("not yours", "", OthersAccess.WRITE));
        Agent agent = agentWith(Access.READ, OthersAccess.NONE);
        Long ownServer = agent.getServers().get(0).getId();

        // NONE: nothing offered, and a call anyway is refused.
        when(model.create(any())).thenReturn(toolUse("t1", "list_agents", Map.of()), endTurn("."));
        runner.run(OWNER, "tok", agent.getId(), "who else is there?");
        ArgumentCaptor<MessageCreateParams> sent = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(model, org.mockito.Mockito.atLeastOnce()).create(sent.capture());
        assertEquals(List.of("fake__read_thing"), offered(sent.getAllValues().get(0)));
        assertEquals(List.of("list_agents: needs othersAccess READ (has NONE)"), log(agent.getId(), Activity.TOOL_DENIED));

        // READ: reading tools work and only the owner's agents show; writing is refused.
        agents.update(OWNER, agent.getId(), new UpdateAgent(null, null, OthersAccess.READ), "");
        when(model.create(any())).thenReturn(
                toolUse("t2", "list_agents", Map.of()),
                toolUse("t3", "get_agent", Map.of("id", stranger.getId())),
                toolUse("t4", "update_agent", Map.of("id", sibling.getId(), "name", "renamed")),
                endTurn("."));
        runner.run(OWNER, "tok", agent.getId(), "look around");
        List<String> calls = log(agent.getId(), Activity.TOOL_CALL);
        String listed = calls.stream().filter(l -> l.startsWith("list_agents {} -> ok: ")).findFirst().orElseThrow();
        assertTrue(listed.contains("#" + sibling.getId() + " sibling (others: NONE, 1 servers)"), listed);
        assertFalse(listed.contains("#" + agent.getId() + " "), "the agent does not list itself: " + listed);
        assertTrue(calls.stream().noneMatch(l -> l.contains("not yours")), "another account's agent must never show");
        assertTrue(calls.stream().anyMatch(l -> l.startsWith("get_agent {id=" + stranger.getId() + "} -> error: no such agent")), calls.toString());
        assertEquals("update_agent: needs othersAccess WRITE (has READ)", log(agent.getId(), Activity.TOOL_DENIED).get(0));
        assertEquals("sibling", agents.get(OWNER, sibling.getId()).getName());

        // WRITE: the sibling can be changed, and both logs say so; the agent itself stays out of reach.
        agents.update(OWNER, agent.getId(), new UpdateAgent(null, null, OthersAccess.WRITE), "");
        when(model.create(any())).thenReturn(
                toolUse("t5", "set_mcp_access", Map.of("id", sibling.getId(), "serverId", siblingTodos, "access", "WRITE")),
                toolUse("t6", "set_mcp_access", Map.of("id", agent.getId(), "serverId", ownServer, "access", "WRITE")),
                toolUse("t7", "update_agent", Map.of("id", agent.getId(), "othersAccess", "WRITE")),
                endTurn("."));
        runner.run(OWNER, "tok", agent.getId(), "escalate");
        assertEquals(Access.WRITE, agents.get(OWNER, sibling.getId()).getServers().get(0).getAccess());
        assertEquals("by agent 'runner' (#" + agent.getId() + "): server 'todos' access READ -> WRITE",
                log(sibling.getId(), Activity.CONFIG_CHANGED).get(0));
        assertEquals(Access.READ, agents.get(OWNER, agent.getId()).getServers().get(0).getAccess(), "it must not widen its own access");
        List<String> denied = log(agent.getId(), Activity.TOOL_DENIED);
        assertEquals("update_agent: an agent cannot change its own configuration", denied.get(0));
        assertEquals("set_mcp_access: an agent cannot change its own configuration", denied.get(1));
    }

    @Test
    void aDeadServerIsSkippedNotFatal() {
        Agent agent = agents.create(OWNER, new NewAgent("lonely", "", OthersAccess.NONE));
        agents.updateServer(OWNER, agent.getId(), agent.getServers().get(0).getId(),
                new UpdateMcpServer(null, "http://localhost:1/nothing", null, null, null), "");
        when(model.create(any())).thenReturn(endTurn("nothing to do"));

        assertEquals("nothing to do", runner.run(OWNER, "tok", agent.getId(), "hi").reply());
        assertTrue(log(agent.getId(), Activity.TOOL_CALL).get(0).startsWith("server 'todos': could not connect"));
        assertFalse(log(agent.getId(), Activity.RUN_FINISHED).isEmpty());
    }

    // --- scripted answers, in the SDK's own types ---

    static Message toolUse(String id, String name, Map<String, Object> input) {
        return message(List.of(ContentBlock.ofToolUse(ToolUseBlock.builder().id(id).name(name).input(JsonValue.from(input))
                .caller(DirectCaller.builder().type(JsonValue.from("direct")).build()).build())),
                StopReason.TOOL_USE);
    }

    static Message endTurn(String text) {
        return message(List.of(ContentBlock.ofText(TextBlock.builder().text(text).citations(Optional.empty()).build())), StopReason.END_TURN);
    }

    static Message message(List<ContentBlock> content, StopReason reason) {
        return Message.builder()
                .id("msg")
                .model("test")
                .role(JsonValue.from("assistant"))
                .type(JsonValue.from("message"))
                .content(content)
                .stopReason(reason)
                .stopSequence(Optional.empty())
                .stopDetails(Optional.empty())
                .container(Optional.empty())
                .usage(Usage.builder().inputTokens(1).outputTokens(1)
                        .cacheCreationInputTokens(Optional.empty()).cacheReadInputTokens(Optional.empty())
                        .serverToolUse(Optional.empty()).serviceTier(Optional.empty()).cacheCreation(Optional.empty()).inferenceGeo(Optional.empty()).build())
                .build();
    }
}
