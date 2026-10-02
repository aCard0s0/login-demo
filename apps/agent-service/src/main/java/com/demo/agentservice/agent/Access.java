package com.demo.agentservice.agent;

/**
 * What an agent may do through one MCP server. READ offers and allows only the tools the server itself
 * marks read-only; WRITE offers them all. A tool with no annotation counts as writing.
 */
public enum Access {
    READ, WRITE;

    public boolean allows(boolean toolIsReadOnly) {
        return this == WRITE || toolIsReadOnly;
    }
}
