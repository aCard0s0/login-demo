package com.demo.todoservice.todo;

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

    /** How many todos exist across every account. Public: a total gives away nobody's list. */
    public long count() {
        return todos.count();
    }

    public List<Todo> list(String owner) {
        return todos.findByOwnerOrderByIdAsc(owner);
    }

    public Todo add(String owner, String title) {
        return todos.save(new Todo(owner, cleanTitle(title)));
    }

    @Transactional
    public Todo toggle(String owner, Long id) {
        Todo todo = mine(owner, id);
        todo.setDone(!todo.isDone());
        return todos.save(todo);
    }

    /**
     * Edits an existing todo. A null field means "leave it alone", so renaming a todo does not force the caller
     * to send back a done flag it never touched -- and cannot flip one by omitting it.
     */
    @Transactional
    public Todo update(String owner, Long id, String title, Boolean done) {
        Todo todo = mine(owner, id);
        if (title != null) {
            todo.setTitle(cleanTitle(title));
        }
        if (done != null) {
            todo.setDone(done);
        }
        return todos.save(todo);
    }

    @Transactional
    public void delete(String owner, Long id) {
        if (todos.deleteByIdAndOwner(id, owner) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    /** The caller's own todo, or 404. Someone else's id is "not found" rather than "forbidden", so neither leaks. */
    private Todo mine(String owner, Long id) {
        return todos.findByIdAndOwner(id, owner)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static String cleanTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title is required");
        }
        return title.strip();
    }
}
