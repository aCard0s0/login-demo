package com.demo.agentservice.mcp;

import com.demo.agentservice.activity.Activity;
import com.demo.agentservice.activity.ActivityLog;
import com.demo.agentservice.agent.entities.Agent;
import com.demo.agentservice.agent.AgentService;
import com.demo.agentservice.agent.ServerUrls;
import com.demo.auth.client.Caller;
import com.demo.auth.client.JwtVerifier;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ErrorCodes;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import io.modelcontextprotocol.spec.McpSchema.InitializeRequest;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCNotification;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse.JSONRPCError;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * The MCP server an external agent connects to, at {@code POST /mcp?agent=<id>}: one of a user's agents,
 * seen from outside as a set of tools. What is offered and what is allowed is that agent's permissions,
 * decided here on every request and never left to whoever is on the other end.
 *
 * <p>Stateless on purpose, like the todo server: no session, nothing kept between requests. Each request
 * carries the token and the agent id, and each one re-reads the agent, so a change the owner makes on the
 * page is seen by the very next call. The handler is written out rather than built with the SDK's static tool
 * list, because the tools differ per agent and per request.
 *
 * <p>Who may connect: any token whose subject owns the agent -- the owner's own login token, or the long-lived
 * {@code AGENT} token minted for this one agent (whose {@code agent} claim must match). Refusals come back as
 * JSON-RPC errors, which is what the agent on the other end can show its user.
 */
@Configuration
public class AgentMcpEndpoint {

    static final String AUTHORIZATION = "authorization";
    static final String AGENT = "agent";

    @Bean
    ServletRegistrationBean<HttpServletStatelessServerTransport> agentMcpServlet(AgentService agents, ActivityLog activity, JwtVerifier jwt, ServerUrls urls) {
        HttpServletStatelessServerTransport transport = HttpServletStatelessServerTransport.builder()
                .messageEndpoint("/mcp")
                .contextExtractor(request -> McpTransportContext.create(Map.of(
                        AUTHORIZATION, Objects.requireNonNullElse(request.getHeader("Authorization"), ""),
                        AGENT, Objects.requireNonNullElse(request.getParameter(AGENT), ""))))
                .build();
        transport.setMcpHandler(new Handler(transport.protocolVersions(), agents, activity, jwt, urls));
        ServletRegistrationBean<HttpServletStatelessServerTransport> servlet = new ServletRegistrationBean<>(transport, "/mcp");
        servlet.setName("agent-mcp");
        servlet.setAsyncSupported(true);
        return servlet;
    }

    /** One request: whose token, the raw bearer to forward, and the agent it names -- all read afresh. */
    private record Session(Caller caller, String bearer, Agent agent) {}

    static final class Handler implements McpStatelessServerHandler {

        private static final Logger log = LoggerFactory.getLogger(Handler.class);

        private final McpJsonMapper json = McpJsonDefaults.getMapper();
        private final List<String> protocolVersions;
        private final AgentService agents;
        private final ActivityLog activity;
        private final JwtVerifier jwt;
        private final ServerUrls urls;

        Handler(List<String> protocolVersions, AgentService agents, ActivityLog activity, JwtVerifier jwt, ServerUrls urls) {
            this.protocolVersions = protocolVersions;
            this.agents = agents;
            this.activity = activity;
            this.jwt = jwt;
            this.urls = urls;
        }

        @Override
        public Mono<JSONRPCResponse> handleRequest(McpTransportContext context, JSONRPCRequest request) {
            return Mono.fromCallable(() -> {
                try {
                    Object result = switch (request.method()) {
                        case McpSchema.METHOD_PING -> Map.of();
                        case McpSchema.METHOD_INITIALIZE -> initialize(session(context), json.convertValue(request.params(), InitializeRequest.class));
                        case McpSchema.METHOD_TOOLS_LIST -> new ListToolsResult(tools(session(context)), null);
                        case McpSchema.METHOD_TOOLS_CALL -> call(session(context), json.convertValue(request.params(), CallToolRequest.class));
                        default -> throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "method not found: " + request.method());
                    };
                    return JSONRPCResponse.result(request.id(), result);
                } catch (ResponseStatusException e) {
                    int code = e.getStatusCode() == HttpStatus.NOT_IMPLEMENTED ? ErrorCodes.METHOD_NOT_FOUND : ErrorCodes.INVALID_REQUEST;
                    return JSONRPCResponse.error(request.id(), new JSONRPCError(code,
                            e.getReason() == null ? e.getStatusCode().toString() : e.getReason()));
                } catch (IllegalArgumentException e) {
                    // params that do not fit the method's shape: a JSON-RPC error the client can read, not a 500.
                    return JSONRPCResponse.error(request.id(), new JSONRPCError(ErrorCodes.INVALID_PARAMS, "invalid params for " + request.method()));
                } catch (RuntimeException e) {
                    log.warn("{} failed", request.method(), e);
                    return JSONRPCResponse.error(request.id(), new JSONRPCError(ErrorCodes.INTERNAL_ERROR, "internal error"));
                }
            });
        }

        @Override
        public Mono<Void> handleNotification(McpTransportContext context, JSONRPCNotification notification) {
            return Mono.empty(); // initialized, cancelled, progress: nothing here keeps state to update
        }

        /**
         * The token and agent id on this request, checked: a bad token is 401, an agent not the caller's is "not found".
         * An agent token names its agent in a claim, so with one the {@code agent} parameter may be left off; a login
         * token has no such claim and needs it.
         */
        private Session session(McpTransportContext context) {
            String authorization = (String) context.get(AUTHORIZATION);
            Caller caller = jwt.callerOf(authorization);
            String param = (String) context.get(AGENT);
            Long id;
            if (param.isBlank() && caller.agentId() != null) {
                id = caller.agentId();
            } else {
                try {
                    id = Long.valueOf(param);
                } catch (NumberFormatException e) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "which agent? connect to /mcp?agent=<id>, or use an agent token");
                }
            }
            // An agent token for another agent gets the same answer as another owner's id: nothing to learn from it.
            if (!caller.mayActAs(id)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such agent");
            }
            return new Session(caller, authorization.replaceFirst("(?i)^Bearer ", ""), agents.get(caller, id));
        }

        private InitializeResult initialize(Session s, InitializeRequest in) {
            Implementation client = in.clientInfo();
            activity.record(s.agent().getId(), Activity.CONNECTED,
                    client == null ? "client unknown" : client.name() + " " + Objects.requireNonNullElse(client.version(), ""));
            String version = protocolVersions.contains(in.protocolVersion()) ? in.protocolVersion() : protocolVersions.get(protocolVersions.size() - 1);
            String instructions = s.agent().getInstructions().isBlank() ? null : s.agent().getInstructions();
            return new InitializeResult(version, ServerCapabilities.builder().tools(false).build(),
                    new Implementation("agent-service: " + s.agent().getName(), "0.0.1"), instructions);
        }

        private List<Tool> tools(Session s) {
            AgentTools builtins = new AgentTools(s.caller(), s.agent(), agents, activity);
            return Stream.concat(builtins.defs(s.agent().getOthersAccess()).stream(),
                    McpTools.list(s.agent(), s.bearer(), activity, urls).stream()).toList();
        }

        /** Runs one tool -- or refuses it -- and says what happened in the agent's log either way. */
        private CallToolResult call(Session s, CallToolRequest in) {
            Map<String, Object> args = in.arguments() == null ? Map.of() : in.arguments();
            String what = in.name() + " " + args;
            AgentTools builtins = new AgentTools(s.caller(), s.agent(), agents, activity);
            ToolResult result;
            try {
                result = builtins.has(in.name()) ? builtins.call(in.name(), args) : McpTools.call(s.agent(), s.bearer(), in.name(), args, urls);
                activity.record(s.agent().getId(), Activity.TOOL_CALL,
                        what + (result.error() ? " -> error: " : " -> ok: ") + ActivityLog.brief(result.summary()));
            } catch (AccessDenied denied) {
                activity.record(s.agent().getId(), Activity.TOOL_DENIED, in.name() + ": " + denied.getMessage());
                result = ToolResult.error("denied: " + denied.getMessage());
            } catch (RuntimeException e) {
                // The owner's log gets the detail; the model gets a flat message, not our hostnames and stack.
                activity.record(s.agent().getId(), Activity.TOOL_CALL, what + " -> error: " + ActivityLog.brief(e.getMessage()));
                result = ToolResult.error("error: the tool call failed; the agent's activity log has the detail");
            }
            CallToolResult.Builder out = CallToolResult.builder()
                    .content(result.content().isEmpty() ? List.of(new TextContent("(empty)")) : result.content())
                    .isError(result.error());
            if (result.structuredContent() != null) {   // the builder refuses a null; absent is simply absent
                out.structuredContent(result.structuredContent());
            }
            return out.build();
        }
    }
}
