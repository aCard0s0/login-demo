package com.demo.agentservice.agent;

import com.demo.agentservice.activity.Activity;
import com.demo.agentservice.activity.ActivityLog;
import com.demo.agentservice.activity.ActivityResponse;
import com.demo.agentservice.agent.dto.*;
import com.demo.agentservice.agent.entities.Agent;
import com.demo.agentservice.token.AgentTokens;
import com.demo.auth.client.Caller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Every endpoint here is private: who is asking comes from the caller's token, never from the request body,
 * and what that answer is allowed to reach is {@link AgentService}'s decision rather than this class's.
 *
 * <p>Only the owner's own tokens are accepted: {@link com.demo.auth.client.CallerResolver} answers an agent
 * token with 403 before any method here runs. An agent token (role {@code AGENT}, pinned to one agent) is for
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
    private final AgentTokens tokens;
    private final ServerUrls urls;

    public AgentController(AgentService agents, ActivityLog activity, AgentTokens tokens, ServerUrls urls) {
        this.agents = agents;
        this.activity = activity;
        this.tokens = tokens;
        this.urls = urls;
    }

    @GetMapping
    public List<AgentResponse> list(Caller caller) {
        return agents.list(caller).stream().map(a -> AgentResponse.of(a, urls)).toList();
    }

    @PostMapping
    public AgentResponse create(Caller caller, @RequestBody NewAgent in) {
        return AgentResponse.of(agents.create(caller, in), urls);
    }

    @GetMapping("/{id}")
    public AgentResponse one(Caller caller, @PathVariable Long id) {
        return AgentResponse.of(agents.get(caller, id), urls);
    }

    @PatchMapping("/{id}")
    public AgentResponse update(Caller caller, @PathVariable Long id, @RequestBody UpdateAgent in) {
        return AgentResponse.of(agents.update(caller, id, in, BY_OWNER), urls);
    }

    @DeleteMapping("/{id}")
    public void delete(Caller caller, @PathVariable Long id) {
        agents.delete(caller, id);
    }

    @PostMapping("/{id}/servers")
    public McpServerResponse addServer(Caller caller, @PathVariable Long id, @RequestBody NewMcpServer in) {
        return McpServerResponse.of(agents.addServer(caller, id, in, BY_OWNER), urls);
    }

    @PatchMapping("/{id}/servers/{serverId}")
    public McpServerResponse updateServer(Caller caller, @PathVariable Long id, @PathVariable Long serverId,
                                          @RequestBody UpdateMcpServer in) {
        return McpServerResponse.of(agents.updateServer(caller, id, serverId, in, BY_OWNER), urls);
    }

    @DeleteMapping("/{id}/servers/{serverId}")
    public void removeServer(Caller caller, @PathVariable Long id, @PathVariable Long serverId) {
        agents.removeServer(caller, id, serverId, BY_OWNER);
    }

    /**
     * A long-lived token for an external agent to connect to {@code /mcp?agent=<id>} as this agent. Shown once
     * and never stored; {@link #revokeToken} kills it early, as does the user's "revoke access".
     */
    @PostMapping("/{id}/token")
    public Map<String, String> token(Caller caller, @PathVariable Long id) {
        Agent agent = agents.get(caller, id);
        String token = tokens.issue(caller.userId(), agent.getId());
        activity.record(agent.getId(), Activity.CONFIG_CHANGED, "agent token issued");
        return Map.of("token", token);
    }

    /**
     * Kills every token ever made for this one agent and nothing else: the owner's login and their other agents'
     * tokens live on. Bites in every service within its ten-second poll of the revocation feed.
     */
    @PostMapping("/{id}/token/revoke")
    public void revokeToken(Caller caller, @PathVariable Long id) {
        Agent agent = agents.get(caller, id);
        tokens.revoke(agent.getId());
        activity.record(agent.getId(), Activity.CONFIG_CHANGED, "agent tokens revoked");
    }

    /** The agent's history, newest first. Reading it needs owning the agent, like everything else here. */
    @GetMapping("/{id}/activity")
    public List<ActivityResponse> activity(Caller caller, @PathVariable Long id) {
        return activity.recent(agents.get(caller, id).getId()).stream().map(ActivityResponse::of).toList();
    }
}
