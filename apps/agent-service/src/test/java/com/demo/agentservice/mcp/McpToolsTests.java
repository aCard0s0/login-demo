package com.demo.agentservice.mcp;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;

class McpToolsTests {

    /** An owner's URL keeps its query string on the way to the SDK, or every server keyed by one is unreachable. */
    @Test
    void theEndpointKeepsPathAndQuery() {
        assertEquals("/mcp?agent=5", McpTools.endpoint(URI.create("http://web:3000/mcp?agent=5")));
        assertEquals("/mcp", McpTools.endpoint(URI.create("http://todo-service:9082/mcp")));
        assertEquals("/", McpTools.endpoint(URI.create("http://host")));
        assertEquals("/a%20b?x=1&y=2", McpTools.endpoint(URI.create("https://host:8443/a%20b?x=1&y=2")));
    }
}
