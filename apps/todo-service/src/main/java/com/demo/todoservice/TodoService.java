package com.demo.todoservice;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class TodoService {

    private final TodoRepository todos;

    public TodoService(TodoRepository todos) {
        this.todos = todos;
    }

    public List<Todo> list(String owner) {
        return todos.findByOwnerOrderByIdAsc(owner);
    }

    public Todo add(String owner, String title) {
        if (title == null || title.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title is required");
        }
        return todos.save(new Todo(owner, title.strip()));
    }

    @Transactional
    public Todo toggle(String owner, Long id) {
        Todo todo = todos.findByIdAndOwner(id, owner)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        todo.setDone(!todo.isDone());
        return todos.save(todo);
    }

    @Transactional
    public void delete(String owner, Long id) {
        if (todos.deleteByIdAndOwner(id, owner) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }
}
