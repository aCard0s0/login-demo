package com.demo.todoservice.stats;

import com.demo.todoservice.todo.TodoService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Deliberately unauthenticated, for the landing page. Nothing here is scoped to an account. */
@RestController
public class StatsController {

    private final TodoService todos;

    public StatsController(TodoService todos) {
        this.todos = todos;
    }

    @GetMapping("/api/public/todos/stats")
    public PublicStats stats() {
        return new PublicStats(todos.count());
    }
}
