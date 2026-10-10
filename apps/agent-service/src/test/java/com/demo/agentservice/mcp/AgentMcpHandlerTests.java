package com.demo.agentservice.mcp;

import com.demo.agentservice.activity.ActivityLog;
import com.demo.agentservice.agent.AgentService;
import com.demo.agentservice.agent.ServerUrls;
import com.demo.agentservice.agent.entities.Agent;
import com.demo.agentservice.agent.entities.OthersAccess;
import com.demo.auth.client.Caller;
import com.demo.auth.client.JwtVerifier;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The handler alone, with the log unable to write: what the model is told must still be what the tool did. */
class AgentMcpHandlerTests {

    @Test
    void aLogLineThatCannotBeWrittenDoesNotTurnTheResultIntoAFailure() {
        Caller owner = new Caller("100", "USER");
        Agent agent = new Agent("100", "solo", "", OthersAccess.READ);
        agent.setId(7L);
        AgentService agents = mock(AgentService.class);
        when(agents.get(owner, 7L)).thenReturn(agent);
        when(agents.list(owner)).thenReturn(List.of(agent));
        ActivityLog activity = mock(ActivityLog.class);
        when(activity.record(any(), any(), any())).thenThrow(new IllegalStateException("database is away"));
        JwtVerifier jwt = mock(JwtVerifier.class);
        when(jwt.callerOf("Bearer tok")).thenReturn(owner);

        AgentMcpEndpoint.Handler handler = new AgentMcpEndpoint.Handler(List.of("2025-06-18"), agents, activity, jwt, mock(ServerUrls.class));
        McpTransportContext context = McpTransportContext.create(Map.of("authorization", "Bearer tok", "agent", "7"));
        JSONRPCResponse response = handler.handleRequest(context, new JSONRPCRequest("2.0", McpSchema.METHOD_TOOLS_CALL, 1,
                Map.of("name", "list_agents", "arguments", Map.of()))).block();

        assertNull(response.error(), "the tool ran; a lost log line is not the tool failing: " + response.error());
        CallToolResult result = (CallToolResult) response.result();
        assertFalse(Boolean.TRUE.equals(result.isError()));
        assertEquals("no other agents", ((TextContent) result.content().get(0)).text());
    }
}
