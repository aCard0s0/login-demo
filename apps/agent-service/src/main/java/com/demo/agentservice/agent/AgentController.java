package com.demo.agentservice.agent;

import com.demo.agentservice.activity.Activity;
import com.demo.agentservice.activity.ActivityLog;
import com.demo.agentservice.activity.ActivityResponse;
import com.demo.agentservice.token.AgentTokens;
import com.demo.agentservice.token.Caller;
import com.demo.agentservice.token.JwtVerifier;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Every endpoint here is private: who is asking comes from the caller's token, never from the request body,
 * and what that answer is allowed to reach is {@link AgentService}'s decision rather than this class's.
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

    @GetMapping
    public List<AgentResponse> list(@RequestHeader(value = "Authorization", required = false) String authz) {
        return agents.list(jwt.callerOf(authz)).stream().map(AgentResponse::of).toList();
    }

    @PostMapping
    public AgentResponse create(@RequestHeader(value = "Authorization", required = false) String authz,
                                @RequestBody NewAgent in) {
        return AgentResponse.of(agents.create(jwt.callerOf(authz), in));
    }

    @GetMapping("/{id}")
    public AgentResponse one(@RequestHeader(value = "Authorization", required = false) String authz,
                             @PathVariable Long id) {
        return AgentResponse.of(agents.get(jwt.callerOf(authz), id));
    }

    @PatchMapping("/{id}")
    public AgentResponse update(@RequestHeader(value = "Authorization", required = false) String authz,
                                @PathVariable Long id, @RequestBody UpdateAgent in) {
        return AgentResponse.of(agents.update(jwt.callerOf(authz), id, in, BY_OWNER));
    }

    @DeleteMapping("/{id}")
    public void delete(@RequestHeader(value = "Authorization", required = false) String authz,
                       @PathVariable Long id) {
        agents.delete(jwt.callerOf(authz), id);
    }

    @PostMapping("/{id}/servers")
    public McpServerResponse addServer(@RequestHeader(value = "Authorization", required = false) String authz,
                                       @PathVariable Long id, @RequestBody NewMcpServer in) {
        return McpServerResponse.of(agents.addServer(jwt.callerOf(authz), id, in, BY_OWNER));
    }

    @PatchMapping("/{id}/servers/{serverId}")
    public McpServerResponse updateServer(@RequestHeader(value = "Authorization", required = false) String authz,
                                          @PathVariable Long id, @PathVariable Long serverId,
                                          @RequestBody UpdateMcpServer in) {
        return McpServerResponse.of(agents.updateServer(jwt.callerOf(authz), id, serverId, in, BY_OWNER));
    }

    @DeleteMapping("/{id}/servers/{serverId}")
    public void removeServer(@RequestHeader(value = "Authorization", required = false) String authz,
                             @PathVariable Long id, @PathVariable Long serverId) {
        agents.removeServer(jwt.callerOf(authz), id, serverId, BY_OWNER);
    }

    /**
     * A long-lived token for an external agent to connect to {@code /mcp?agent=<id>} as this agent. Shown once
     * and never stored; the account's "revoke access" is what kills it early.
     */
    @PostMapping("/{id}/token")
    public Map<String, String> token(@RequestHeader(value = "Authorization", required = false) String authz,
                                     @PathVariable Long id) {
        Caller caller = jwt.callerOf(authz);
        Agent agent = agents.get(caller, id);
        String token = tokens.issue(caller.accountId(), agent.getId());
        activity.record(agent.getId(), Activity.CONFIG_CHANGED, "agent token issued");
        return Map.of("token", token);
    }

    /** The agent's history, newest first. Reading it needs owning the agent, like everything else here. */
    @GetMapping("/{id}/activity")
    public List<ActivityResponse> activity(@RequestHeader(value = "Authorization", required = false) String authz,
                                           @PathVariable Long id) {
        Caller caller = jwt.callerOf(authz);
        return activity.recent(agents.get(caller, id).getId()).stream().map(ActivityResponse::of).toList();
    }
}
