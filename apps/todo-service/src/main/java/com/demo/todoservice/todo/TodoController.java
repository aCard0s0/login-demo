package com.demo.todoservice.todo;

import com.demo.auth.client.Caller;
import com.demo.todoservice.todo.dto.NewTodo;
import com.demo.todoservice.todo.dto.TodoResponse;
import com.demo.todoservice.todo.dto.UpdateTodo;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Every endpoint here is private: who is asking comes from the caller's token, never from the request body,
 * and what that answer is allowed to reach is {@link TodoService}'s decision rather than this class's.
 *
 * <p>Only a user's own tokens are accepted: {@link com.demo.auth.client.CallerResolver} answers an agent token
 * with 403 before any method here runs. An agent token reaches the todos over {@code /mcp} through
 * agent-service, where its owner's READ/WRITE setting and activity log apply; let in here, a READ agent
 * could delete with its 30-day token and skip both.
 */
@RestController
@RequestMapping("/api/todos")
public class TodoController {

    private final TodoService todos;

    public TodoController(TodoService todos) {
        this.todos = todos;
    }

    @GetMapping
    public List<TodoResponse> all(Caller caller) {
        return todos.list(caller).stream().map(TodoResponse::of).toList();
    }

    @PostMapping
    public TodoResponse add(Caller caller, @RequestBody NewTodo in) {
        return TodoResponse.of(todos.add(caller, in.title()));
    }

    @PutMapping("/{id}")
    public TodoResponse toggle(Caller caller, @PathVariable Long id) {
        return TodoResponse.of(todos.toggle(caller, id));
    }

    /** Edits an existing todo. Only the fields present in the body change; the rest are left as they are. */
    @PatchMapping("/{id}")
    public TodoResponse update(Caller caller, @PathVariable Long id,
                               @RequestBody UpdateTodo in) {
        return TodoResponse.of(todos.update(caller, id, in.title(), in.done()));
    }

    @DeleteMapping("/{id}")
    public void delete(Caller caller, @PathVariable Long id) {
        todos.delete(caller, id);
    }
}
