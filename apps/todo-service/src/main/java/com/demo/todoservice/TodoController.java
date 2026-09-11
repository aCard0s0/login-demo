package com.demo.todoservice;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/todos")
public class TodoController {

    private final TodoService todos;
    private final AuthClient auth;

    public TodoController(TodoService todos, AuthClient auth) {
        this.todos = todos;
        this.auth = auth;
    }

    public record NewTodo(String title) {}

    public record TodoResponse(Long id, String title, boolean done) {
        static TodoResponse of(Todo t) {
            return new TodoResponse(t.getId(), t.getTitle(), t.isDone());
        }
    }

    @GetMapping
    public List<TodoResponse> all(@RequestHeader(value = "Authorization", required = false) String authz) {
        return todos.list(auth.ownerOf(authz)).stream().map(TodoResponse::of).toList();
    }

    @PostMapping
    public TodoResponse add(@RequestHeader(value = "Authorization", required = false) String authz,
                            @RequestBody NewTodo in) {
        return TodoResponse.of(todos.add(auth.ownerOf(authz), in.title()));
    }

    @PutMapping("/{id}")
    public TodoResponse toggle(@RequestHeader(value = "Authorization", required = false) String authz,
                               @PathVariable Long id) {
        return TodoResponse.of(todos.toggle(auth.ownerOf(authz), id));
    }

    @DeleteMapping("/{id}")
    public void delete(@RequestHeader(value = "Authorization", required = false) String authz,
                       @PathVariable Long id) {
        todos.delete(auth.ownerOf(authz), id);
    }
}
