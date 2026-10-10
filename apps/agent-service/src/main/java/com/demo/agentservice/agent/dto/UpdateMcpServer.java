package com.demo.agentservice.agent.dto;

import com.demo.agentservice.agent.entities.Access;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * A null field means "leave it alone"; an empty {@code authHeader} clears the stored one, an empty {@code readOnlyTools}
 * marks none. The text fields are checked stripped, as they are stored.
 */
public record UpdateMcpServer(@Pattern(regexp = NewMcpServer.NAME, message = NewMcpServer.NAME_RULE) String name,
                              @Size(max = 255, message = "url must be at most 255 characters") String url,
                              @Size(max = 1400, message = "authHeader must be at most 1400 characters") @Pattern(regexp = NewMcpServer.HEADER, message = NewMcpServer.HEADER_RULE) String authHeader,
                              Boolean forwardCallerToken,
                              Access access,
                              List<String> readOnlyTools) {

    public UpdateMcpServer {
        name = name == null ? null : name.strip();
        url = url == null ? null : url.strip();
        authHeader = authHeader == null ? null : authHeader.strip();
    }

    public UpdateMcpServer(String name, String url, String authHeader, Boolean forwardCallerToken, Access access) {
        this(name, url, authHeader, forwardCallerToken, access, null);
    }
}
