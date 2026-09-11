package com.demo.todoservice.todo;

import com.demo.todoservice.token.JwtVerifier;
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

import java.util.List;

/** Every endpoint here is private: the owner comes from the caller's token, never from the request body. */
@RestController
@RequestMapping("/api/todos")
public class TodoController {

    private final TodoService todos;
    private final JwtVerifier jwt;

    public TodoController(TodoService todos, JwtVerifier jwt) {
        this.todos = todos;
        this.jwt = jwt;
    }

    @GetMapping
    public List<TodoResponse> all(@RequestHeader(value = "Authorization", required = false) String authz) {
        return todos.list(jwt.ownerOf(authz)).stream().map(TodoResponse::of).toList();
    }

    @PostMapping
    public TodoResponse add(@RequestHeader(value = "Authorization", required = false) String authz,
                            @RequestBody NewTodo in) {
        return TodoResponse.of(todos.add(jwt.ownerOf(authz), in.title()));
    }

    @PutMapping("/{id}")
    public TodoResponse toggle(@RequestHeader(value = "Authorization", required = false) String authz,
                               @PathVariable Long id) {
        return TodoResponse.of(todos.toggle(jwt.ownerOf(authz), id));
    }

    /** Edits an existing todo. Only the fields present in the body change; the rest are left as they are. */
    @PatchMapping("/{id}")
    public TodoResponse update(@RequestHeader(value = "Authorization", required = false) String authz,
                               @PathVariable Long id,
                               @RequestBody UpdateTodo in) {
        return TodoResponse.of(todos.update(jwt.ownerOf(authz), id, in.title(), in.done()));
    }

    @DeleteMapping("/{id}")
    public void delete(@RequestHeader(value = "Authorization", required = false) String authz,
                       @PathVariable Long id) {
        todos.delete(jwt.ownerOf(authz), id);
    }
}
