package com.demo.agentservice.agent;

/** The stored header value never leaves the service; the page only learns whether there is one. */
public record McpServerResponse(Long id, String name, String url, Access access, boolean forwardCallerToken, boolean hasAuthHeader) {

    public static McpServerResponse of(AgentMcpServer s) {
        return new McpServerResponse(s.getId(), s.getName(), s.getUrl(), s.getAccess(), s.isForwardCallerToken(),
                s.getAuthHeader() != null && !s.getAuthHeader().isBlank());
    }
}
