package com.demo.mcp.server;

import com.demo.auth.client.Caller;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * A service's own MCP server at {@code /mcp}: a fixed list of tools, each the thin MCP face of one service
 * method, run as whoever the request's {@code Authorization} header names.
 *
 * <p>Stateless on purpose: no session id, no event stream, nothing kept between one run and the next. The
 * endpoint sits outside {@code /api}, so the web proxy never forwards it; only agent-service reaches it.
 */
public final class McpEndpoint {

    private static final String AUTHORIZATION = "authorization";

    private McpEndpoint() {}

    /** The servlet serving these tools at {@code /mcp}; return it from a {@code @Bean} method. */
    public static ServletRegistrationBean<HttpServletStatelessServerTransport> servlet(String serverName,
                                                                                      List<SyncToolSpecification> tools) {
        HttpServletStatelessServerTransport transport = HttpServletStatelessServerTransport.builder()
                .messageEndpoint("/mcp")
                .contextExtractor(request -> McpTransportContext.create(
                        Map.of(AUTHORIZATION, Objects.requireNonNullElse(request.getHeader("Authorization"), ""))))
                .build();
        // Building the server wires it into the transport; nothing else holds on to it.
        McpServer.sync(transport)
                .serverInfo(serverName, "0.0.1")
                .capabilities(ServerCapabilities.builder().tools(false).build())
                .tools(tools)
                .build();
        ServletRegistrationBean<HttpServletStatelessServerTransport> servlet = new ServletRegistrationBean<>(transport, "/mcp");
        servlet.setName("mcp");
        servlet.setAsyncSupported(true);
        return servlet;
    }

    /**
     * One tool: resolve the caller from the request's Authorization header with {@code callerOf}, run the
     * action, and render any refusal -- a bad token, a bad argument, a rule the service enforces -- as an
     * error result the model can read rather than a failed request.
     */
    public static SyncToolSpecification tool(Tool tool, Function<String, Caller> callerOf,
                                             BiFunction<Caller, Map<String, Object>, String> action) {
        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((context, request) -> {
                    try {
                        Caller caller = callerOf.apply((String) context.get(AUTHORIZATION));
                        Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();
                        return CallToolResult.builder().addTextContent(action.apply(caller, args)).build();
                    } catch (ResponseStatusException e) {
                        String reason = e.getReason() == null ? e.getStatusCode().toString() : e.getReason();
                        return CallToolResult.builder().isError(true).addTextContent(reason).build();
                    }
                })
                .build();
    }

    /** A tool's input schema: an object with these properties, of which these are required. */
    public static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        return Map.of("type", "object", "properties", properties, "required", required);
    }
}
