package com.demo.agentservice.agent.dto;

import com.demo.agentservice.agent.entities.Access;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * {@code name} is short and lower-case because it prefixes every tool name the model sees. {@code authHeader} is
 * plain text here, and printable ASCII as a header value must be, so its length is its byte count; encrypted it
 * grows to 3 + 4/3 * (12 + length + 16) characters, which must fit the 2000-wide column. The text fields are
 * checked stripped, as they are stored. {@code readOnlyTools}: the tools READ may offer on a server the deployment does not trust; null or
 * empty means none.
 */
public record NewMcpServer(@NotNull(message = NewMcpServer.NAME_RULE) @Pattern(regexp = NewMcpServer.NAME, message = NewMcpServer.NAME_RULE) String name,
                           @Size(max = 255, message = "url must be at most 255 characters") String url,
                           @Size(max = 1400, message = "authHeader must be at most 1400 characters") @Pattern(regexp = NewMcpServer.HEADER, message = NewMcpServer.HEADER_RULE) String authHeader,
                           Boolean forwardCallerToken,
                           @NotNull(message = "access must be READ or WRITE") Access access,
                           List<String> readOnlyTools) {

    public static final String NAME = "^[a-z0-9_-]{1,20}$";
    public static final String NAME_RULE = "server name must be 1-20 of a-z, 0-9, _ or -";
    public static final String HEADER = "\\p{Print}*";
    public static final String HEADER_RULE = "authHeader must be printable ASCII";

    public NewMcpServer {
        name = name == null ? null : name.strip();
        url = url == null ? null : url.strip();
        authHeader = authHeader == null ? null : authHeader.strip();
    }

    public NewMcpServer(String name, String url, String authHeader, Boolean forwardCallerToken, Access access) {
        this(name, url, authHeader, forwardCallerToken, access, null);
    }
}
