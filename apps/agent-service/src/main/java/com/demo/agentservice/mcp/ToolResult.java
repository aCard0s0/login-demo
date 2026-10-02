package com.demo.agentservice.mcp;

/** What a tool call came back with, for the model. */
record ToolResult(String text, boolean error) {

    static ToolResult ok(String text) {
        return new ToolResult(text, false);
    }

    static ToolResult error(String text) {
        return new ToolResult(text, true);
    }
}
