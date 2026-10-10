package com.demo.agentservice.agent;

import com.demo.agentservice.activity.Activity;
import com.demo.agentservice.activity.ActivityLog;
import com.demo.agentservice.agent.dto.*;
import com.demo.agentservice.agent.entities.Access;
import com.demo.agentservice.agent.entities.OthersAccess;
import com.demo.agentservice.agent.entities.Agent;
import com.demo.agentservice.agent.entities.AgentMcpServer;
import com.demo.agentservice.agent.entities.AgentRepository;
import com.demo.auth.client.Caller;
import com.demo.web.errors.Bad;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Everything an owner may do to its agents, and the only place that decides whose agents a caller sees. Every
 * lookup goes through {@link #get}, so another user's agent is "not found" everywhere -- in the REST API
 * and in the tools one agent uses on another alike.
 *
 * <p>Every change is written to the agent's activity log, prefixed with who made it when it was not the owner
 * at the keyboard but one of their agents.
 *
 * <p>The limits on what comes in -- name required, column widths, the server-name alphabet -- are the
 * annotations on the DTOs, checked here and not at the controller, so the tools one agent uses on another
 * are held to the same rule as the page. A violation is a 400 with the annotation's own sentence.
 */
@Service
public class AgentService {

    static final String TODO_SERVER = "todos";

    /** The joined, comma-separated column. */
    static final int MAX_READ_ONLY_TOOLS = 2000;

    private final AgentRepository agents;
    private final ActivityLog activity;
    private final ServerUrls urls;
    private final Validator validator;
    private final String todoMcpUrl;

    public AgentService(AgentRepository agents, ActivityLog activity, ServerUrls urls, Validator validator,
                        @Value("${agents.todo-mcp-url:}") String todoMcpUrl) {
        this.agents = agents;
        this.activity = activity;
        this.urls = urls;
        this.validator = validator;
        this.todoMcpUrl = todoMcpUrl == null ? "" : todoMcpUrl.strip();
    }

    /** How many agents exist across every user. Public: a total gives away nobody's setup. */
    public long count() {
        return agents.count();
    }

    public List<Agent> list(Caller caller) {
        return agents.findByOwnerOrderByIdAsc(caller.userId());
    }

    /** The caller's agent, or 404. Someone else's id comes back "not found" rather than "forbidden". */
    public Agent get(Caller caller, Long id) {
        return agents.findByIdAndOwner(id, caller.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such agent"));
    }

    /** A new agent, starting with the built-in todo server as READ when the deployment has one. */
    @Transactional
    public Agent create(Caller caller, NewAgent in) {
        valid(in);
        Agent agent = new Agent(caller.userId(), in.name().strip(), strip(in.instructions()),
                in.othersAccess() == null ? OthersAccess.NONE : in.othersAccess());
        if (!todoMcpUrl.isEmpty()) {
            agent.getServers().add(new AgentMcpServer(agent, TODO_SERVER, todoMcpUrl, null, true, Access.READ));
        }
        agent = agents.save(agent);
        activity.record(agent.getId(), Activity.CONFIG_CHANGED,
                "created" + (todoMcpUrl.isEmpty() ? "" : " with server '" + TODO_SERVER + "' (READ)"));
        return agent;
    }

    /** Edits an agent. A null field means "leave it alone". {@code by} names the actor when it is not the owner. */
    @Transactional
    public Agent update(Caller caller, Long id, UpdateAgent in, String by) {
        valid(in);
        Agent agent = get(caller, id);
        List<String> changes = new ArrayList<>();
        if (in.name() != null && !in.name().strip().equals(agent.getName())) {
            changes.add("name '" + agent.getName() + "' -> '" + in.name().strip() + "'");
            agent.setName(in.name().strip());
        }
        if (in.instructions() != null && !in.instructions().strip().equals(agent.getInstructions())) {
            agent.setInstructions(in.instructions().strip());
            changes.add("instructions edited");
        }
        if (in.othersAccess() != null && in.othersAccess() != agent.getOthersAccess()) {
            changes.add("othersAccess " + agent.getOthersAccess() + " -> " + in.othersAccess());
            agent.setOthersAccess(in.othersAccess());
        }
        return changed(agent, by, changes);
    }

    @Transactional
    public void delete(Caller caller, Long id) {
        agents.delete(get(caller, id));
        activity.deleteFor(id);
    }

    @Transactional
    public AgentMcpServer addServer(Caller caller, Long id, NewMcpServer in, String by) {
        valid(in);
        Agent agent = get(caller, id);
        String name = uniqueServerName(agent, in.name());
        String url = urls.clean(in.url());
        boolean forward = Boolean.TRUE.equals(in.forwardCallerToken());
        checkForward(forward, url);
        AgentMcpServer server = new AgentMcpServer(agent, name, url, blankToNull(in.authHeader()), forward, in.access());
        server.setReadOnlyTools(cleanTools(in.readOnlyTools()));
        agent.getServers().add(server);
        // save() merges, so the row with an id is the one on the saved copy, not the one built above.
        return changed(agent, by, List.of("server '" + name + "' added (" + in.access() + ")")).getServers().stream()
                .filter(s -> s.getName().equals(name)).findFirst().orElseThrow();
    }

    @Transactional
    public AgentMcpServer updateServer(Caller caller, Long id, Long serverId, UpdateMcpServer in, String by) {
        valid(in);
        Agent agent = get(caller, id);
        AgentMcpServer server = server(agent, serverId);
        List<String> changes = new ArrayList<>();
        String was = server.getName();
        if (in.name() != null && !in.name().strip().equals(was)) {
            server.setName(uniqueServerName(agent, in.name()));
            changes.add("server '" + was + "' renamed '" + server.getName() + "'");
        }
        if (in.url() != null && !in.url().strip().equals(server.getUrl())) {
            server.setUrl(urls.clean(in.url()));
            changes.add("server '" + server.getName() + "' url changed");
        }
        if (in.authHeader() != null) {
            String header = blankToNull(in.authHeader());
            changes.add("server '" + server.getName() + "' auth header " + (header == null ? "cleared" : "set"));
            server.setAuthHeader(header);
        }
        if (in.forwardCallerToken() != null && in.forwardCallerToken() != server.isForwardCallerToken()) {
            server.setForwardCallerToken(in.forwardCallerToken());
            changes.add("server '" + server.getName() + "' forwards caller token " + (in.forwardCallerToken() ? "on" : "off"));
        }
        // Judged on the pair as it will be saved: a URL moved off the trusted list with the forward still on is refused too.
        checkForward(server.isForwardCallerToken(), server.getUrl());
        if (in.access() != null && in.access() != server.getAccess()) {
            changes.add("server '" + server.getName() + "' access " + server.getAccess() + " -> " + in.access());
            server.setAccess(in.access());
        }
        if (in.readOnlyTools() != null) {
            String tools = cleanTools(in.readOnlyTools());
            if (!Objects.equals(tools, server.getReadOnlyTools())) {
                server.setReadOnlyTools(tools);
                changes.add("server '" + server.getName() + "' read-only tools " + (tools == null ? "cleared" : "set: " + tools));
            }
        }
        changed(agent, by, changes);
        return server;
    }

    @Transactional
    public void removeServer(Caller caller, Long id, Long serverId, String by) {
        Agent agent = get(caller, id);
        AgentMcpServer server = server(agent, serverId);
        agent.getServers().remove(server);
        changed(agent, by, List.of("server '" + server.getName() + "' removed"));
    }

    private Agent changed(Agent agent, String by, List<String> changes) {
        Agent saved = agents.save(agent);
        if (!changes.isEmpty()) {
            activity.record(saved.getId(), Activity.CONFIG_CHANGED, (by == null ? "" : by) + String.join(", ", changes));
        }
        return saved;
    }

    private static AgentMcpServer server(Agent agent, Long serverId) {
        return agent.getServers().stream().filter(s -> s.getId().equals(serverId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such server"));
    }

    /** The DTO's own annotations, as a 400 with one violation's sentence: the first by field name, so the same body always gets the same answer. */
    private <T> void valid(T in) {
        validator.validate(in).stream()
                .min(Comparator.comparing((ConstraintViolation<T> v) -> v.getPropertyPath().toString()).thenComparing(ConstraintViolation::getMessage))
                .ifPresent(v -> {
                    throw Bad.request(v.getMessage());
                });
    }

    /**
     * The caller's token is the owner's whole authority -- every service accepts it -- so it goes only to the
     * deployment's own servers, never to a URL an owner typed in, however much the owner trusts it. A server that
     * needs a credential of its own gets a stored header instead.
     */
    private void checkForward(boolean forward, String url) {
        if (forward && !urls.trusted(url)) {
            throw Bad.request("the caller's token is only forwarded to the deployment's own servers; use an authorization header instead");
        }
    }

    /** Unique within the agent, because it becomes the prefix of every tool name the model sees. The alphabet is the DTO's rule. */
    private static String uniqueServerName(Agent agent, String name) {
        if (agent.getServers().stream().anyMatch(s -> s.getName().equals(name))) {
            throw Bad.request("server name already used by this agent");
        }
        return name;
    }

    private static String strip(String s) {
        return s == null ? "" : s.strip();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    /** Tool names as the server names them, stripped, deduplicated and sorted; null when none are left. */
    private static String cleanTools(List<String> tools) {
        if (tools == null) {
            return null;
        }
        String clean = tools.stream().filter(Objects::nonNull).map(String::strip).filter(s -> !s.isEmpty())
                .distinct().sorted().collect(Collectors.joining(","));
        if (clean.length() > MAX_READ_ONLY_TOOLS) {
            throw Bad.request("too many read-only tools");
        }
        return clean.isEmpty() ? null : clean;
    }
}
