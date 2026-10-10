package com.demo.agentservice.agent;

import java.util.List;

/** A null field means "leave it alone"; an empty {@code authHeader} clears the stored one, an empty {@code readOnlyTools} marks none. */
public record UpdateMcpServer(String name, String url, String authHeader, Boolean forwardCallerToken, Access access, List<String> readOnlyTools) {

    public UpdateMcpServer(String name, String url, String authHeader, Boolean forwardCallerToken, Access access) {
        this(name, url, authHeader, forwardCallerToken, access, null);
    }
}
