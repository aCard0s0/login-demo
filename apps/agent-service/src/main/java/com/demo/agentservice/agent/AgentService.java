package com.demo.agentservice.agent;

import com.demo.agentservice.activity.Activity;
import com.demo.agentservice.activity.ActivityLog;
import com.demo.agentservice.token.Caller;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Everything an owner may do to its agents, and the only place that decides whose agents a caller sees. Every
 * lookup goes through {@link #get}, so another account's agent is "not found" everywhere -- in the REST API
 * and in the tools one agent uses on another alike.
 *
 * <p>Every change is written to the agent's activity log, prefixed with who made it when it was not the owner
 * at the keyboard but one of their agents.
 */
@Service
public class AgentService {

    static final Pattern SERVER_NAME = Pattern.compile("^[a-z0-9_-]{1,20}$");
    static final String TODO_SERVER = "todos";

    private final AgentRepository agents;
    private final ActivityLog activity;
    private final String todoMcpUrl;

    public AgentService(AgentRepository agents, ActivityLog activity, @Value("${agents.todo-mcp-url:}") String todoMcpUrl) {
        this.agents = agents;
        this.activity = activity;
        this.todoMcpUrl = todoMcpUrl == null ? "" : todoMcpUrl.strip();
    }

    /** How many agents exist across every account. Public: a total gives away nobody's setup. */
    public long count() {
        return agents.count();
    }

    public List<Agent> list(Caller caller) {
        return agents.findByOwnerOrderByIdAsc(caller.accountId());
    }

    /** The caller's agent, or 404. Someone else's id comes back "not found" rather than "forbidden". */
    public Agent get(Caller caller, Long id) {
        return agents.findByIdAndOwner(id, caller.accountId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such agent"));
    }

    /** A new agent, starting with the built-in todo server as READ when the deployment has one. */
    @Transactional
    public Agent create(Caller caller, NewAgent in) {
        Agent agent = new Agent(caller.accountId(), cleanName(in.name()),
                in.instructions() == null ? "" : in.instructions().strip(),
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
        Agent agent = get(caller, id);
        List<String> changes = new ArrayList<>();
        if (in.name() != null && !in.name().strip().equals(agent.getName())) {
            String name = cleanName(in.name());
            changes.add("name '" + agent.getName() + "' -> '" + name + "'");
            agent.setName(name);
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
        Agent agent = get(caller, id);
        String name = cleanServerName(agent, in.name());
        if (in.access() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "access must be READ or WRITE");
        }
        AgentMcpServer server = new AgentMcpServer(agent, name, cleanUrl(in.url()), blankToNull(in.authHeader()),
                Boolean.TRUE.equals(in.forwardCallerToken()), in.access());
        agent.getServers().add(server);
        // save() merges, so the row with an id is the one on the saved copy, not the one built above.
        return changed(agent, by, List.of("server '" + name + "' added (" + in.access() + ")")).getServers().stream()
                .filter(s -> s.getName().equals(name)).findFirst().orElseThrow();
    }

    @Transactional
    public AgentMcpServer updateServer(Caller caller, Long id, Long serverId, UpdateMcpServer in, String by) {
        Agent agent = get(caller, id);
        AgentMcpServer server = server(agent, serverId);
        List<String> changes = new ArrayList<>();
        String was = server.getName();
        if (in.name() != null && !in.name().strip().equals(was)) {
            server.setName(cleanServerName(agent, in.name()));
            changes.add("server '" + was + "' renamed '" + server.getName() + "'");
        }
        if (in.url() != null && !in.url().strip().equals(server.getUrl())) {
            server.setUrl(cleanUrl(in.url()));
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
        if (in.access() != null && in.access() != server.getAccess()) {
            changes.add("server '" + server.getName() + "' access " + server.getAccess() + " -> " + in.access());
            server.setAccess(in.access());
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

    private static String cleanName(String name) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        if (name.strip().length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is too long");
        }
        return name.strip();
    }

    /** Short and lower-case, because it becomes the prefix of every tool name the model sees; unique within the agent. */
    private static String cleanServerName(Agent agent, String name) {
        String clean = name == null ? "" : name.strip();
        if (!SERVER_NAME.matcher(clean).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "server name must be 1-20 of a-z, 0-9, _ or -");
        }
        if (agent.getServers().stream().anyMatch(s -> s.getName().equals(clean))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "server name already used by this agent");
        }
        return clean;
    }

    private static String cleanUrl(String url) {
        try {
            URI uri = URI.create(url == null ? "" : url.strip());
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null) {
                throw new IllegalArgumentException();
            }
            return uri.toString();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "url must be http(s)://host[:port]/path");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
