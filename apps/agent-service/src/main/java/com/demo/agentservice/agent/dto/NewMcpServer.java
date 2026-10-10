package com.demo.agentservice.agent.dto;

import java.util.List;

/** {@code readOnlyTools}: the tools READ may offer on a server the deployment does not trust; null or empty means none. */
public record NewMcpServer(String name, String url, String authHeader, Boolean forwardCallerToken, Access access, List<String> readOnlyTools) {

    public NewMcpServer(String name, String url, String authHeader, Boolean forwardCallerToken, Access access) {
        this(name, url, authHeader, forwardCallerToken, access, null);
    }
}
