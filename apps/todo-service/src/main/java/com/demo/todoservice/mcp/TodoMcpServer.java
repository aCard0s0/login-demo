package com.demo.todoservice.mcp;

import com.demo.auth.client.Caller;
import com.demo.auth.client.JwtVerifier;
import com.demo.mcp.server.McpEndpoint;
import com.demo.todoservice.todo.Todo;
import com.demo.todoservice.todo.TodoService;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.demo.mcp.server.Args.bool;
import static com.demo.mcp.server.Args.number;
import static com.demo.mcp.server.Args.string;
import static com.demo.mcp.server.McpEndpoint.schema;
import static com.demo.mcp.server.McpEndpoint.tool;

/**
 * The same todos, reachable by an agent over MCP at {@code /mcp}. Four tools, each the thin MCP face of one
 * {@link TodoService} method, so the owner rules there apply to an agent exactly as they do to the browser.
 *
 * <p>Who is asking comes from the {@code Authorization} header on the MCP request, carried over from the user
 * who started the agent, and is checked by the same {@link JwtVerifier} as the REST API -- except that this
 * endpoint, unlike the REST API, does take an agent token: agent-service forwards it here after applying the
 * agent's READ/WRITE setting, which is the only reason an agent token is good anywhere. Only {@code list_todos}
 * declares itself read-only; that annotation is what agent-service's READ permission keys on, so leaving it
 * off a tool that writes is the one thing this class must never do.
 *
 * <p>The endpoint itself -- stateless, outside {@code /api} -- is {@link McpEndpoint}'s.
 */
@Configuration
public class TodoMcpServer {

    @Bean
    ServletRegistrationBean<HttpServletStatelessServerTransport> mcpServlet(TodoService todos, JwtVerifier jwt) {
        return McpEndpoint.servlet("todo-service", tools(todos, jwt::callerOf));
    }

    private static List<SyncToolSpecification> tools(TodoService todos, Function<String, Caller> callerOf) {
        return List.of(
                tool(Tool.builder()
                                .name("list_todos")
                                .description("List the user's todos, one per line as '#id [x] title' ([x] done, [ ] open).")
                                .inputSchema(schema(Map.of(), List.of()))
                                .annotations(ToolAnnotations.builder().readOnlyHint(true).build())
                                .build(),
                        callerOf, (caller, args) -> {
                            List<Todo> mine = todos.list(caller);
                            return mine.isEmpty() ? "no todos"
                                    : mine.stream().map(TodoMcpServer::line).collect(Collectors.joining("\n"));
                        }),
                tool(Tool.builder()
                                .name("add_todo")
                                .description("Add a todo for the user.")
                                .inputSchema(schema(Map.of("title", Map.of("type", "string")), List.of("title")))
                                .build(),
                        callerOf, (caller, args) -> line(todos.add(caller, string(args, "title")))),
                tool(Tool.builder()
                                .name("update_todo")
                                .description("Edit a todo's title and/or done flag. Fields left out are unchanged.")
                                .inputSchema(schema(Map.of(
                                        "id", Map.of("type", "integer"),
                                        "title", Map.of("type", "string"),
                                        "done", Map.of("type", "boolean")), List.of("id")))
                                .build(),
                        callerOf, (caller, args) -> line(todos.update(caller, number(args, "id"), string(args, "title"), bool(args, "done")))),
                tool(Tool.builder()
                                .name("delete_todo")
                                .description("Delete a todo.")
                                .inputSchema(schema(Map.of("id", Map.of("type", "integer")), List.of("id")))
                                .build(),
                        callerOf, (caller, args) -> {
                            todos.delete(caller, number(args, "id"));
                            return "deleted";
                        }));
    }

    private static String line(Todo todo) {
        return "#" + todo.getId() + " [" + (todo.isDone() ? "x" : " ") + "] " + todo.getTitle();
    }
}
