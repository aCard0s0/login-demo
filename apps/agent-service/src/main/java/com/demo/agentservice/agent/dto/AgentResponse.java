package com.demo.agentservice.agent.dto;

import com.demo.agentservice.agent.entities.Access;
import com.demo.agentservice.agent.entities.OthersAccess;

import com.demo.agentservice.agent.ServerUrls;
import com.demo.agentservice.agent.entities.Agent;

import java.util.List;

public record AgentResponse(Long id, String name, String instructions, OthersAccess othersAccess, List<McpServerResponse> servers) {

    public static AgentResponse of(Agent a, ServerUrls urls) {
        return new AgentResponse(a.getId(), a.getName(), a.getInstructions(), a.getOthersAccess(),
                a.getServers().stream().map(s -> McpServerResponse.of(s, urls)).toList());
    }
}
