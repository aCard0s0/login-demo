package com.demo.agentservice.agent;

import java.util.List;

/**
 * The stored header value never leaves the service; the page only learns whether there is one. {@code trusted}
 * says whether the deployment trusts this server's own read-only annotations, or READ keys on {@code readOnlyTools}.
 */
public record McpServerResponse(Long id, String name, String url, Access access, boolean forwardCallerToken, boolean hasAuthHeader,
                                boolean trusted, List<String> readOnlyTools) {

    public static McpServerResponse of(AgentMcpServer s, ServerUrls urls) {
        return new McpServerResponse(s.getId(), s.getName(), s.getUrl(), s.getAccess(), s.isForwardCallerToken(),
                s.getAuthHeader() != null && !s.getAuthHeader().isBlank(),
                urls.trusted(s.getUrl()), s.readOnlyToolSet().stream().sorted().toList());
    }
}
