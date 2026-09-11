package com.demo.todoservice;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/api/todos")
public class TodoController {

    public record Todo(long id, String title, boolean done) {}

    public record NewTodo(String title) {}

    private final Map<String, List<Todo>> byUser = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private final RestClient auth;

    public TodoController(@Value("${auth.url}") String authUrl) {
        this.auth = RestClient.create(authUrl);
    }

    /** Resolves the bearer token against auth-service. Throws 401 if it is not a live session. */
    private String user(String authorization) {
        String token = authorization == null ? "" : authorization.replaceFirst("(?i)^Bearer ", "");
        try {
            Map<?, ?> body = auth.get()
                    .uri(b -> b.path("/api/verify").queryParam("token", token).build())
                    .retrieve()
                    .body(Map.class);
            return (String) body.get("username");
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid token");
        }
    }

    private List<Todo> list(String user) {
        return byUser.computeIfAbsent(user, k -> new java.util.concurrent.CopyOnWriteArrayList<>());
    }

    @GetMapping
    public List<Todo> all(@RequestHeader(value = "Authorization", required = false) String authz) {
        return list(user(authz));
    }

    @PostMapping
    public Todo add(@RequestHeader(value = "Authorization", required = false) String authz,
                    @RequestBody NewTodo in) {
        Todo todo = new Todo(ids.incrementAndGet(), in.title(), false);
        list(user(authz)).add(todo);
        return todo;
    }

    @PutMapping("/{id}")
    public Todo toggle(@RequestHeader(value = "Authorization", required = false) String authz,
                       @PathVariable long id) {
        List<Todo> todos = list(user(authz));
        for (int i = 0; i < todos.size(); i++) {
            Todo t = todos.get(i);
            if (t.id() == id) {
                Todo flipped = new Todo(t.id(), t.title(), !t.done());
                todos.set(i, flipped);
                return flipped;
            }
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    @DeleteMapping("/{id}")
    public void delete(@RequestHeader(value = "Authorization", required = false) String authz,
                       @PathVariable long id) {
        list(user(authz)).removeIf(t -> t.id() == id);
    }
}
