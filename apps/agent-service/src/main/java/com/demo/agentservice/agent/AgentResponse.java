package com.demo.agentservice.agent;

import java.util.List;

public record AgentResponse(Long id, String name, String instructions, OthersAccess othersAccess, List<McpServerResponse> servers) {

    public static AgentResponse of(Agent a) {
        return new AgentResponse(a.getId(), a.getName(), a.getInstructions(), a.getOthersAccess(),
                a.getServers().stream().map(McpServerResponse::of).toList());
    }
}
