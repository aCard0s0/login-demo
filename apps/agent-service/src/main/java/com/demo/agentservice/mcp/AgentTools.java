package com.demo.agentservice.mcp;

import com.demo.agentservice.activity.ActivityLog;
import com.demo.agentservice.agent.Access;
import com.demo.agentservice.agent.Agent;
import com.demo.agentservice.agent.AgentMcpServer;
import com.demo.agentservice.agent.AgentService;
import com.demo.agentservice.agent.NewMcpServer;
import com.demo.agentservice.agent.OthersAccess;
import com.demo.agentservice.agent.UpdateAgent;
import com.demo.agentservice.agent.UpdateMcpServer;
import com.demo.agentservice.token.Caller;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * What one agent may do to its owner's <em>other</em> agents, as tools. Three read, four write, gated by the
 * agent's {@code othersAccess} -- read again from the database on every call, so the owner's change bites
 * between two calls like it does for MCP servers.
 *
 * <p>Every tool goes through {@link AgentService} as the owner, so the owner rules are the same ones the REST
 * API enforces and another account's agent is "not found" here too. An agent may never change its own
 * configuration: with that door open, one call to {@code set_mcp_access} would be all the escalation it takes.
 */
class AgentTools {

    private static final List<String> READ_TOOLS = List.of("list_agents", "get_agent", "get_agent_activity");
    private static final List<String> WRITE_TOOLS = List.of("update_agent", "add_mcp_server", "set_mcp_access", "remove_mcp_server");

    private final Caller caller;
    private final Long selfId;
    private final String by;
    private final AgentService agents;
    private final ActivityLog activity;

    AgentTools(Caller caller, Agent self, AgentService agents, ActivityLog activity) {
        this.caller = caller;
        this.selfId = self.getId();
        this.by = "by agent '" + self.getName() + "' (#" + self.getId() + "): ";
        this.agents = agents;
        this.activity = activity;
    }

    /** The tools to offer, for the access the agent has right now. */
    List<Tool> defs(OthersAccess access) {
        List<Tool> defs = new ArrayList<>();
        if (access.reads()) {
            defs.add(def("list_agents", "List the owner's other agents: id, name, access to other agents, number of MCP servers.", Map.of(), List.of()));
            defs.add(def("get_agent", "An agent's name, instructions, access to other agents and MCP servers (id, name, url, access).",
                    Map.of("id", integer()), List.of("id")));
            defs.add(def("get_agent_activity", "An agent's recent history: connections, tool calls, refusals and configuration changes, newest first.",
                    Map.of("id", integer()), List.of("id")));
        }
        if (access.writes()) {
            defs.add(def("update_agent", "Change an agent's name, instructions and/or access to other agents (NONE, READ, WRITE). Fields left out are unchanged.",
                    Map.of("id", integer(), "name", string(), "instructions", string(),
                            "othersAccess", Map.of("type", "string", "enum", List.of("NONE", "READ", "WRITE"))), List.of("id")));
            defs.add(def("add_mcp_server", "Attach an MCP server (Streamable HTTP URL) to an agent with READ or WRITE access.",
                    Map.of("id", integer(), "name", string(), "url", string(), "access", accessEnum()), List.of("id", "name", "url", "access")));
            defs.add(def("set_mcp_access", "Change an agent's access to one of its MCP servers: READ or WRITE.",
                    Map.of("id", integer(), "serverId", integer(), "access", accessEnum()), List.of("id", "serverId", "access")));
            defs.add(def("remove_mcp_server", "Detach an MCP server from an agent.",
                    Map.of("id", integer(), "serverId", integer()), List.of("id", "serverId")));
        }
        return defs;
    }

    boolean has(String name) {
        return READ_TOOLS.contains(name) || WRITE_TOOLS.contains(name);
    }

    ToolResult call(String name, Map<String, Object> args) {
        OthersAccess now = agents.get(caller, selfId).getOthersAccess();
        boolean write = WRITE_TOOLS.contains(name);
        if (write ? !now.writes() : !now.reads()) {
            throw new AccessDenied("needs othersAccess " + (write ? "WRITE" : "READ") + " (has " + now + ")");
        }
        Long id = name.equals("list_agents") ? null : id(args, "id");
        if (write && selfId.equals(id)) {
            throw new AccessDenied("an agent cannot change its own configuration");
        }
        try {
            return ToolResult.ok(switch (name) {
                case "list_agents" -> agents.list(caller).stream()
                        .filter(a -> !a.getId().equals(selfId))
                        .map(a -> "#" + a.getId() + " " + a.getName() + " (others: " + a.getOthersAccess() + ", " + a.getServers().size() + " servers)")
                        .collect(Collectors.collectingAndThen(Collectors.joining("\n"), s -> s.isEmpty() ? "no other agents" : s));
                case "get_agent" -> describe(agents.get(caller, id));
                case "get_agent_activity" -> {
                    agents.get(caller, id);
                    yield activity.recent(id).stream()
                            .map(a -> a.getAt() + " " + a.getKind() + ": " + a.getDetail())
                            .collect(Collectors.collectingAndThen(Collectors.joining("\n"), s -> s.isEmpty() ? "no activity" : s));
                }
                case "update_agent" -> describe(agents.update(caller, id,
                        new UpdateAgent(string(args, "name"), string(args, "instructions"), othersAccess(args)), by));
                case "add_mcp_server" -> {
                    AgentMcpServer added = agents.addServer(caller, id,
                            new NewMcpServer(string(args, "name"), string(args, "url"), null, false, access(args)), by);
                    yield "added server #" + added.getId() + " '" + added.getName() + "' (" + added.getAccess() + ")";
                }
                case "set_mcp_access" -> {
                    AgentMcpServer changed = agents.updateServer(caller, id, id(args, "serverId"),
                            new UpdateMcpServer(null, null, null, null, access(args)), by);
                    yield "server '" + changed.getName() + "' is now " + changed.getAccess();
                }
                case "remove_mcp_server" -> {
                    agents.removeServer(caller, id, id(args, "serverId"), by);
                    yield "removed";
                }
                default -> throw new AccessDenied("no such tool: " + name);
            });
        } catch (ResponseStatusException e) {
            return ToolResult.error(e.getReason() == null ? e.getStatusCode().toString() : e.getReason());
        }
    }

    private static String describe(Agent a) {
        StringBuilder out = new StringBuilder("#" + a.getId() + " " + a.getName() + "\nothersAccess: " + a.getOthersAccess()
                + "\ninstructions: " + (a.getInstructions().isEmpty() ? "(none)" : a.getInstructions()) + "\nservers:");
        if (a.getServers().isEmpty()) {
            out.append(" none");
        }
        for (AgentMcpServer s : a.getServers()) {
            out.append("\n  #").append(s.getId()).append(' ').append(s.getName()).append(' ').append(s.getUrl()).append(' ').append(s.getAccess());
        }
        return out.toString();
    }

    /** Reading tools say so in their annotation, like the built-in todo server's listing does. */
    private static Tool def(String name, String description, Map<String, Object> properties, List<String> required) {
        return Tool.builder().name(name).description(description)
                .inputSchema(Map.of("type", "object", "properties", properties, "required", required))
                .annotations(ToolAnnotations.builder().readOnlyHint(READ_TOOLS.contains(name)).build())
                .build();
    }

    private static Map<String, Object> integer() {
        return Map.of("type", "integer");
    }

    private static Map<String, Object> string() {
        return Map.of("type", "string");
    }

    private static Map<String, Object> accessEnum() {
        return Map.of("type", "string", "enum", List.of("READ", "WRITE"));
    }

    private static Long id(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new AccessDenied(key + " must be a number");
        }
    }

    private static String string(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static Access access(Map<String, Object> args) {
        try {
            return Access.valueOf(String.valueOf(args.get("access")).toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new AccessDenied("access must be READ or WRITE");
        }
    }

    private static OthersAccess othersAccess(Map<String, Object> args) {
        Object value = args.get("othersAccess");
        if (value == null) {
            return null;
        }
        try {
            return OthersAccess.valueOf(String.valueOf(value).toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new AccessDenied("othersAccess must be NONE, READ or WRITE");
        }
    }
}
