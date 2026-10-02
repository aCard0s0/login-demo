package com.demo.todoservice.mcp;

import com.demo.todoservice.todo.Todo;
import com.demo.todoservice.todo.TodoService;
import com.demo.todoservice.token.Caller;
import com.demo.todoservice.token.JwtVerifier;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * The same todos, reachable by an agent over MCP at {@code /mcp}. Four tools, each the thin MCP face of one
 * {@link TodoService} method, so the owner rules there apply to an agent exactly as they do to the browser.
 *
 * <p>Who is asking comes from the {@code Authorization} header on the MCP request, carried over from the user
 * who started the agent, and is checked by the same {@link JwtVerifier} as the REST API. Only {@code list_todos}
 * declares itself read-only; that annotation is what agent-service's READ permission keys on, so leaving it
 * off a tool that writes is the one thing this class must never do.
 *
 * <p>Stateless on purpose: no session id, no event stream, nothing kept between one run and the next. The
 * endpoint sits outside {@code /api}, so the web proxy never forwards it; only agent-service reaches it.
 */
@Configuration
public class TodoMcpServer {

    static final String AUTHORIZATION = "authorization";

    @Bean
    ServletRegistrationBean<HttpServletStatelessServerTransport> mcpServlet(TodoService todos, JwtVerifier jwt) {
        HttpServletStatelessServerTransport transport = HttpServletStatelessServerTransport.builder()
                .messageEndpoint("/mcp")
                .contextExtractor(request -> McpTransportContext.create(
                        Map.of(AUTHORIZATION, Objects.requireNonNullElse(request.getHeader("Authorization"), ""))))
                .build();
        // Building the server wires it into the transport; nothing else holds on to it.
        McpServer.sync(transport)
                .serverInfo("todo-service", "0.0.1")
                .capabilities(ServerCapabilities.builder().tools(false).build())
                .tools(tools(todos, jwt))
                .build();
        ServletRegistrationBean<HttpServletStatelessServerTransport> servlet = new ServletRegistrationBean<>(transport, "/mcp");
        servlet.setName("mcp");
        servlet.setAsyncSupported(true);
        return servlet;
    }

    private static List<SyncToolSpecification> tools(TodoService todos, JwtVerifier jwt) {
        return List.of(
                tool(Tool.builder()
                                .name("list_todos")
                                .description("List the user's todos, one per line as '#id [x] title' ([x] done, [ ] open).")
                                .inputSchema(schema(Map.of(), List.of()))
                                .annotations(ToolAnnotations.builder().readOnlyHint(true).build())
                                .build(),
                        jwt, (caller, args) -> {
                            List<Todo> mine = todos.list(caller);
                            return mine.isEmpty() ? "no todos"
                                    : mine.stream().map(TodoMcpServer::line).collect(Collectors.joining("\n"));
                        }),
                tool(Tool.builder()
                                .name("add_todo")
                                .description("Add a todo for the user.")
                                .inputSchema(schema(Map.of("title", Map.of("type", "string")), List.of("title")))
                                .build(),
                        jwt, (caller, args) -> line(todos.add(caller, string(args, "title")))),
                tool(Tool.builder()
                                .name("update_todo")
                                .description("Edit a todo's title and/or done flag. Fields left out are unchanged.")
                                .inputSchema(schema(Map.of(
                                        "id", Map.of("type", "integer"),
                                        "title", Map.of("type", "string"),
                                        "done", Map.of("type", "boolean")), List.of("id")))
                                .build(),
                        jwt, (caller, args) -> line(todos.update(caller, id(args), string(args, "title"), bool(args, "done")))),
                tool(Tool.builder()
                                .name("delete_todo")
                                .description("Delete a todo.")
                                .inputSchema(schema(Map.of("id", Map.of("type", "integer")), List.of("id")))
                                .build(),
                        jwt, (caller, args) -> {
                            todos.delete(caller, id(args));
                            return "deleted";
                        }));
    }

    /** One tool: resolve the caller from the request's token, run the action, and render any refusal as an error result. */
    private static SyncToolSpecification tool(Tool tool, JwtVerifier jwt,
                                              BiFunction<Caller, Map<String, Object>, String> action) {
        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((context, request) -> {
                    try {
                        Caller caller = jwt.callerOf((String) context.get(AUTHORIZATION));
                        Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();
                        return CallToolResult.builder().addTextContent(action.apply(caller, args)).build();
                    } catch (ResponseStatusException e) {
                        String reason = e.getReason() == null ? e.getStatusCode().toString() : e.getReason();
                        return CallToolResult.builder().isError(true).addTextContent(reason).build();
                    }
                })
                .build();
    }

    private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        return Map.of("type", "object", "properties", properties, "required", required);
    }

    private static String line(Todo todo) {
        return "#" + todo.getId() + " [" + (todo.isDone() ? "x" : " ") + "] " + todo.getTitle();
    }

    private static Long id(Map<String, Object> args) {
        Object value = args.get("id");
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "id must be a number");
        }
    }

    private static String string(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static Boolean bool(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null ? null : Boolean.valueOf(String.valueOf(value));
    }
}
