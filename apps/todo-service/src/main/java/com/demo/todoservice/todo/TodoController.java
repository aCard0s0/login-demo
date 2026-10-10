package com.demo.todoservice.todo;

import com.demo.auth.client.Caller;
import com.demo.auth.client.JwtVerifier;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Every endpoint here is private: who is asking comes from the caller's token, never from the request body,
 * and what that answer is allowed to reach is {@link TodoService}'s decision rather than this class's.
 *
 * <p>Only a user's own tokens are accepted. An agent token reaches the todos over {@code /mcp} through
 * agent-service, where its owner's READ/WRITE setting and activity log apply; let in here, a READ agent
 * could delete with its 30-day token and skip both.
 */
@RestController
@RequestMapping("/api/todos")
public class TodoController {

    private final TodoService todos;
    private final JwtVerifier jwt;

    public TodoController(TodoService todos, JwtVerifier jwt) {
        this.todos = todos;
        this.jwt = jwt;
    }

    /** The user behind the token, or 401; an agent token is 403 because its way in is MCP, not this API. */
    private Caller user(String authz) {
        Caller caller = jwt.callerOf(authz);
        if (caller.isAgent()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "an agent token can only reach the todos over MCP");
        }
        return caller;
    }

    @GetMapping
    public List<TodoResponse> all(@RequestHeader(value = "Authorization", required = false) String authz) {
        return todos.list(user(authz)).stream().map(TodoResponse::of).toList();
    }

    @PostMapping
    public TodoResponse add(@RequestHeader(value = "Authorization", required = false) String authz,
                            @RequestBody NewTodo in) {
        return TodoResponse.of(todos.add(user(authz), in.title()));
    }

    @PutMapping("/{id}")
    public TodoResponse toggle(@RequestHeader(value = "Authorization", required = false) String authz,
                               @PathVariable Long id) {
        return TodoResponse.of(todos.toggle(user(authz), id));
    }

    /** Edits an existing todo. Only the fields present in the body change; the rest are left as they are. */
    @PatchMapping("/{id}")
    public TodoResponse update(@RequestHeader(value = "Authorization", required = false) String authz,
                               @PathVariable Long id,
                               @RequestBody UpdateTodo in) {
        return TodoResponse.of(todos.update(user(authz), id, in.title(), in.done()));
    }

    @DeleteMapping("/{id}")
    public void delete(@RequestHeader(value = "Authorization", required = false) String authz,
                       @PathVariable Long id) {
        todos.delete(user(authz), id);
    }
}
