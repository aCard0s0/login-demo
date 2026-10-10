package com.demo.agentservice.agent;

import java.util.List;

public record AgentResponse(Long id, String name, String instructions, OthersAccess othersAccess, List<McpServerResponse> servers) {

    public static AgentResponse of(Agent a, ServerUrls urls) {
        return new AgentResponse(a.getId(), a.getName(), a.getInstructions(), a.getOthersAccess(),
                a.getServers().stream().map(s -> McpServerResponse.of(s, urls)).toList());
    }
}
