package com.demo.agentservice.run;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import com.demo.agentservice.activity.Activity;
import com.demo.agentservice.activity.ActivityLog;
import com.demo.agentservice.agent.Agent;
import com.demo.agentservice.agent.AgentMcpServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The MCP servers one run may use: connected at the start, closed at the end, and consulted on every call.
 *
 * <p>Permission is decided in two places on purpose. The tools <em>offered</em> to the model are filtered by
 * the access the server had when the run started, so a READ server's writing tools are not even described.
 * Each <em>call</em> is checked again against the row as it is in the database right then, so an owner who
 * flips a server to READ while a run is going is obeyed from the next call -- and a model that calls a tool it
 * was never offered is refused all the same.
 */
// ponytail: tool annotations are the listTools() snapshot from the start of the run; a server that changes a
// tool's readOnlyHint mid-run is seen on the next run. Re-list per call if that ever matters.
class McpTools implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpTools.class);

    /** Anthropic's rule for tool names. */
    private static final int MAX_NAME = 64;

    /** One offered tool: which client answers it, which server row it belongs to, and the tool as the server described it. */
    private record Bound(McpSyncClient client, Long serverId, String serverName, io.modelcontextprotocol.spec.McpSchema.Tool tool) {}

    private final Map<String, Bound> offered = new LinkedHashMap<>();
    private final List<McpSyncClient> clients = new ArrayList<>();
    private final Supplier<Agent> fresh;

    private McpTools(Supplier<Agent> fresh) {
        this.fresh = fresh;
    }

    /**
     * Connects to every server of the agent. A server that cannot be reached is logged and skipped, so one
     * dead URL does not take the whole run down. {@code fresh} re-reads the agent for the per-call check.
     */
    static McpTools connect(Agent agent, String bearer, ActivityLog activity, Supplier<Agent> fresh) {
        McpTools tools = new McpTools(fresh);
        for (AgentMcpServer server : agent.getServers()) {
            String auth = server.isForwardCallerToken() ? "Bearer " + bearer : server.getAuthHeader();
            McpSyncClient client = null;
            try {
                URI url = URI.create(server.getUrl());
                String base = url.getScheme() + "://" + url.getRawAuthority();
                String endpoint = url.getRawPath() == null || url.getRawPath().isEmpty() ? "/" : url.getRawPath();
                HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(base)
                        .endpoint(endpoint)
                        .connectTimeout(Duration.ofSeconds(5))
                        .httpRequestCustomizer((request, method, uri, body, context) -> {
                            if (auth != null) {
                                request.header("Authorization", auth);
                            }
                        })
                        .build();
                client = McpClient.sync(transport)
                        .requestTimeout(Duration.ofSeconds(30))
                        .initializationTimeout(Duration.ofSeconds(10))
                        .build();
                client.initialize();
                tools.clients.add(client);
                for (io.modelcontextprotocol.spec.McpSchema.Tool tool : client.listTools().tools()) {
                    if (!server.getAccess().allows(readOnly(tool))) {
                        continue; // not even described to the model
                    }
                    String name = offeredName(server.getName(), tool.name());
                    if (tools.offered.put(name, new Bound(client, server.getId(), server.getName(), tool)) != null) {
                        log.warn("agent {}: two tools on server '{}' map to '{}'; the last one wins", agent.getId(), server.getName(), name);
                    }
                }
            } catch (Exception e) {
                if (client != null) {
                    tools.clients.remove(client);
                    client.close();
                }
                activity.record(agent.getId(), Activity.TOOL_CALL,
                        "server '" + server.getName() + "': could not connect: " + ActivityLog.brief(e.getMessage()));
            }
        }
        return tools;
    }

    /** The tools to offer the model, in Anthropic's shape. */
    List<Tool> defs() {
        return offered.entrySet().stream().map(e -> toolDef(e.getKey(), e.getValue().tool())).toList();
    }

    boolean has(String name) {
        return offered.containsKey(name);
    }

    /** Runs one tool, after checking the server row as it is right now. */
    ToolResult call(String name, Map<String, Object> args) {
        Bound bound = offered.get(name);
        if (bound == null) {
            throw new AccessDenied("no such tool: " + name);
        }
        AgentMcpServer now = fresh.get().getServers().stream()
                .filter(s -> s.getId().equals(bound.serverId())).findFirst()
                .orElseThrow(() -> new AccessDenied("server '" + bound.serverName() + "' was removed"));
        if (!now.getAccess().allows(readOnly(bound.tool()))) {
            throw new AccessDenied("needs WRITE on server '" + now.getName() + "' (has " + now.getAccess() + ")");
        }
        CallToolResult result = bound.client().callTool(new CallToolRequest(bound.tool().name(), args));
        return new ToolResult(text(result), Boolean.TRUE.equals(result.isError()));
    }

    @Override
    public void close() {
        for (McpSyncClient client : clients) {
            try {
                client.closeGracefully();
            } catch (Exception e) {
                log.debug("closing an MCP client: {}", e.getMessage());
            }
        }
    }

    /** The one rule READ keys on. A tool that does not say it is read-only is taken to write. */
    static boolean readOnly(io.modelcontextprotocol.spec.McpSchema.Tool tool) {
        return tool.annotations() != null && Boolean.TRUE.equals(tool.annotations().readOnlyHint());
    }

    /** {@code server__tool}, in the characters Anthropic allows. Built-in tool names never contain "__". */
    static String offeredName(String server, String tool) {
        String name = (server + "__" + tool).replaceAll("[^A-Za-z0-9_-]", "_");
        return name.length() > MAX_NAME ? name.substring(0, MAX_NAME) : name;
    }

    @SuppressWarnings("unchecked")
    static Tool toolDef(String name, io.modelcontextprotocol.spec.McpSchema.Tool tool) {
        Tool.InputSchema.Builder schema = Tool.InputSchema.builder();
        Map<String, Object> input = tool.inputSchema() == null ? Map.of() : tool.inputSchema();
        Tool.InputSchema.Properties.Builder properties = Tool.InputSchema.Properties.builder();
        if (input.get("properties") instanceof Map<?, ?> props) {
            props.forEach((k, v) -> properties.putAdditionalProperty(String.valueOf(k), JsonValue.from(v)));
        }
        schema.properties(properties.build());
        if (input.get("required") instanceof List<?> required) {
            schema.required(required.stream().map(String::valueOf).toList());
        }
        return Tool.builder()
                .name(name)
                .description(tool.description() == null ? tool.name() : tool.description())
                .inputSchema(schema.build())
                .build();
    }

    private static String text(CallToolResult result) {
        if (result.content() == null || result.content().isEmpty()) {
            return "";
        }
        return result.content().stream()
                .map(c -> c instanceof TextContent t ? t.text() : String.valueOf(c))
                .collect(Collectors.joining("\n"));
    }
}
