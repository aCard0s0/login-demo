package com.demo.agentservice.mcp;

import com.demo.agentservice.activity.Activity;
import com.demo.agentservice.activity.ActivityLog;
import com.demo.agentservice.agent.Agent;
import com.demo.agentservice.agent.AgentMcpServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The MCP servers an agent's owner attached, seen through that agent's permissions.
 *
 * <p>Permission is decided in two places on purpose. {@link #list} offers only the tools the access on each
 * server row allows, so a READ server's writing tools are not even described to the connecting agent.
 * {@link #call} then checks the row as it is in the database right then, so an owner who flips a server to
 * READ between two calls is obeyed from the next one -- and an agent that calls a tool it was never offered
 * is refused all the same.
 */
// ponytail: stateless, so every call connects to the one downstream server afresh: initialize, list its tools
// (for the read-only annotation), call, close. Three round trips per call. A short per-URL cache is the upgrade.
final class McpTools {

    private static final Logger log = LoggerFactory.getLogger(McpTools.class);

    /** The usual ceiling on tool names in model APIs; kept so a long server + tool pair still fits. */
    private static final int MAX_NAME = 64;

    private McpTools() {}

    /**
     * Every tool the agent may use right now, named {@code server__tool}. A server that cannot be reached is
     * written to the log and skipped, so one dead URL does not hide the others.
     */
    static List<Tool> list(Agent agent, String bearer, ActivityLog activity) {
        Map<String, Tool> offered = new LinkedHashMap<>();
        for (AgentMcpServer server : agent.getServers()) {
            try (McpSyncClient client = connect(server, bearer)) {
                for (Tool tool : client.listTools().tools()) {
                    if (server.getAccess().allows(readOnly(tool))) {
                        Tool mine = renamed(server, tool);
                        // Two long names cut to the same 64 characters: offer neither as that name, rather than one at random.
                        if (offered.putIfAbsent(mine.name(), mine) != null) {
                            log.warn("agent {} server '{}': tool '{}' truncates to '{}' like another; not offered", agent.getId(), server.getName(), tool.name(), mine.name());
                            offered.remove(mine.name());
                        }
                    }
                }
            } catch (Exception e) {
                // Clients list tools often; one line per failure, not one per listing, or the log is nothing else.
                activity.recordOnce(agent.getId(), Activity.TOOL_CALL,
                        "server '" + server.getName() + "': could not connect: " + ActivityLog.brief(e.getMessage()));
            }
        }
        return new ArrayList<>(offered.values());
    }

    /** Runs one tool on the server its name points at, after checking that server's row as it is right now. */
    static ToolResult call(Agent agent, String bearer, String name, Map<String, Object> args) {
        AgentMcpServer server = serverOf(agent, name);
        try (McpSyncClient client = connect(server, bearer)) {
            List<Tool> matching = client.listTools().tools().stream()
                    .filter(t -> offeredName(server.getName(), t.name()).equals(name)).toList();
            if (matching.size() > 1) {
                throw new AccessDenied("ambiguous tool: " + matching.size() + " tools on server '" + server.getName() + "' truncate to " + name);
            }
            Tool tool = matching.stream().findFirst().orElseThrow(() -> new AccessDenied("no such tool: " + name));
            if (!server.getAccess().allows(readOnly(tool))) {
                throw new AccessDenied("needs WRITE on server '" + server.getName() + "' (has " + server.getAccess() + ")");
            }
            CallToolResult result = client.callTool(new CallToolRequest(tool.name(), args));
            return new ToolResult(text(result), Boolean.TRUE.equals(result.isError()));
        }
    }

    /** The server whose name prefixes the tool name; the longest match when one server's name begins another's. */
    private static AgentMcpServer serverOf(Agent agent, String name) {
        AgentMcpServer best = null;
        for (AgentMcpServer server : agent.getServers()) {
            String prefix = offeredName(server.getName(), "");
            if (name.startsWith(prefix) && (best == null || prefix.length() > offeredName(best.getName(), "").length())) {
                best = server;
            }
        }
        if (best == null) {
            throw new AccessDenied("no such tool: " + name);
        }
        return best;
    }

    private static McpSyncClient connect(AgentMcpServer server, String bearer) {
        String auth = server.isForwardCallerToken() ? "Bearer " + bearer : server.getAuthHeader();
        URI url = URI.create(server.getUrl());
        String base = url.getScheme() + "://" + url.getRawAuthority();
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(base)
                .endpoint(endpoint(url))
                .connectTimeout(Duration.ofSeconds(5))
                .httpRequestCustomizer((request, method, uri, body, context) -> {
                    if (auth != null) {
                        request.header("Authorization", auth);
                    }
                })
                .build();
        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(30))
                .initializationTimeout(Duration.ofSeconds(10))
                .build();
        try {
            client.initialize();
        } catch (RuntimeException e) {
            client.close();
            throw e;
        }
        return client;
    }

    /** Path and query of the stored URL, as the SDK's endpoint: {@code /mcp?agent=5} must keep its {@code ?agent=5}. */
    static String endpoint(URI url) {
        String path = url.getRawPath() == null || url.getRawPath().isEmpty() ? "/" : url.getRawPath();
        return url.getRawQuery() == null ? path : path + "?" + url.getRawQuery();
    }

    /** The one rule READ keys on. A tool that does not say it is read-only is taken to write. */
    static boolean readOnly(Tool tool) {
        return tool.annotations() != null && Boolean.TRUE.equals(tool.annotations().readOnlyHint());
    }

    /** {@code server__tool}, in the characters every client allows. Built-in tool names never contain "__". */
    static String offeredName(String server, String tool) {
        String name = (server + "__" + tool).replaceAll("[^A-Za-z0-9_-]", "_");
        return name.length() > MAX_NAME ? name.substring(0, MAX_NAME) : name;
    }

    /** The tool as the server described it, under the name the connecting agent will call it by. */
    private static Tool renamed(AgentMcpServer server, Tool tool) {
        Tool.Builder copy = Tool.builder()
                .name(offeredName(server.getName(), tool.name()))
                .description(tool.description() == null ? tool.name() : tool.description())
                .inputSchema(tool.inputSchema() == null ? Map.of("type", "object") : tool.inputSchema());
        if (tool.annotations() != null) {
            copy.annotations(tool.annotations());
        }
        return copy.build();
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
