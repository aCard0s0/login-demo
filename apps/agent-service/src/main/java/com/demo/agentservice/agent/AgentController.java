package com.demo.agentservice.agent;

import com.demo.agentservice.activity.Activity;
import com.demo.agentservice.activity.ActivityLog;
import com.demo.agentservice.activity.ActivityResponse;
import com.demo.agentservice.token.AgentTokens;
import com.demo.agentservice.token.Caller;
import com.demo.agentservice.token.JwtVerifier;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Every endpoint here is private: who is asking comes from the caller's token, never from the request body,
 * and what that answer is allowed to reach is {@link AgentService}'s decision rather than this class's.
 *
 * <p>Only the owner's own tokens are accepted. An agent token (role {@code AGENT}, pinned to one agent) is for
 * {@code /mcp} alone: let in here, it could widen its own access or mint itself fresh tokens -- the very
 * escalation the agent tools refuse.
 */
@RestController
@RequestMapping("/api/agents")
public class AgentController {

    /** Changes made here are the owner's own; the prefix is for changes one agent makes to another. */
    static final String BY_OWNER = "";

    private final AgentService agents;
    private final ActivityLog activity;
    private final JwtVerifier jwt;
    private final AgentTokens tokens;

    public AgentController(AgentService agents, ActivityLog activity, JwtVerifier jwt, AgentTokens tokens) {
        this.agents = agents;
        this.activity = activity;
        this.jwt = jwt;
        this.tokens = tokens;
    }

    /** The owner behind the token, or 401; an agent token is 403 because it has no business on this API. */
    private Caller owner(String authz) {
        Caller caller = jwt.callerOf(authz);
        if (caller.agentId() != null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "an agent token can only connect to /mcp");
        }
        return caller;
    }

    @GetMapping
    public List<AgentResponse> list(@RequestHeader(value = "Authorization", required = false) String authz) {
        return agents.list(owner(authz)).stream().map(AgentResponse::of).toList();
    }

    @PostMapping
    public AgentResponse create(@RequestHeader(value = "Authorization", required = false) String authz,
                                @RequestBody NewAgent in) {
        return AgentResponse.of(agents.create(owner(authz), in));
    }

    @GetMapping("/{id}")
    public AgentResponse one(@RequestHeader(value = "Authorization", required = false) String authz,
                             @PathVariable Long id) {
        return AgentResponse.of(agents.get(owner(authz), id));
    }

    @PatchMapping("/{id}")
    public AgentResponse update(@RequestHeader(value = "Authorization", required = false) String authz,
                                @PathVariable Long id, @RequestBody UpdateAgent in) {
        return AgentResponse.of(agents.update(owner(authz), id, in, BY_OWNER));
    }

    @DeleteMapping("/{id}")
    public void delete(@RequestHeader(value = "Authorization", required = false) String authz,
                       @PathVariable Long id) {
        agents.delete(owner(authz), id);
    }

    @PostMapping("/{id}/servers")
    public McpServerResponse addServer(@RequestHeader(value = "Authorization", required = false) String authz,
                                       @PathVariable Long id, @RequestBody NewMcpServer in) {
        return McpServerResponse.of(agents.addServer(owner(authz), id, in, BY_OWNER));
    }

    @PatchMapping("/{id}/servers/{serverId}")
    public McpServerResponse updateServer(@RequestHeader(value = "Authorization", required = false) String authz,
                                          @PathVariable Long id, @PathVariable Long serverId,
                                          @RequestBody UpdateMcpServer in) {
        return McpServerResponse.of(agents.updateServer(owner(authz), id, serverId, in, BY_OWNER));
    }

    @DeleteMapping("/{id}/servers/{serverId}")
    public void removeServer(@RequestHeader(value = "Authorization", required = false) String authz,
                             @PathVariable Long id, @PathVariable Long serverId) {
        agents.removeServer(owner(authz), id, serverId, BY_OWNER);
    }

    /**
     * A long-lived token for an external agent to connect to {@code /mcp?agent=<id>} as this agent. Shown once
     * and never stored; the account's "revoke access" is what kills it early.
     */
    @PostMapping("/{id}/token")
    public Map<String, String> token(@RequestHeader(value = "Authorization", required = false) String authz,
                                     @PathVariable Long id) {
        Caller caller = owner(authz);
        Agent agent = agents.get(caller, id);
        String token = tokens.issue(caller.accountId(), agent.getId());
        activity.record(agent.getId(), Activity.CONFIG_CHANGED, "agent token issued");
        return Map.of("token", token);
    }

    /** The agent's history, newest first. Reading it needs owning the agent, like everything else here. */
    @GetMapping("/{id}/activity")
    public List<ActivityResponse> activity(@RequestHeader(value = "Authorization", required = false) String authz,
                                           @PathVariable Long id) {
        Caller caller = owner(authz);
        return activity.recent(agents.get(caller, id).getId()).stream().map(ActivityResponse::of).toList();
    }
}
