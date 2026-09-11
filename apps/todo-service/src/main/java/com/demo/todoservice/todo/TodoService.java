package com.demo.todoservice.todo;

import com.demo.todoservice.token.Caller;
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

    /** The caller's own todos, or everybody's when the role says so. */
    public List<Todo> list(Caller caller) {
        return caller.readsEveryone()
                ? todos.findAllByOrderByIdAsc()
                : todos.findByOwnerOrderByIdAsc(caller.accountId());
    }

    /** A todo is always created for the caller, whatever their role: there is no "add this to someone else". */
    public Todo add(Caller caller, String title) {
        return todos.save(new Todo(caller.accountId(), cleanTitle(title)));
    }

    @Transactional
    public Todo toggle(Caller caller, Long id) {
        Todo todo = writable(caller, id);
        todo.setDone(!todo.isDone());
        return todos.save(todo);
    }

    /**
     * Edits an existing todo. A null field means "leave it alone", so renaming a todo does not force the caller
     * to send back a done flag it never touched -- and cannot flip one by omitting it.
     */
    @Transactional
    public Todo update(Caller caller, Long id, String title, Boolean done) {
        Todo todo = writable(caller, id);
        if (title != null) {
            todo.setTitle(cleanTitle(title));
        }
        if (done != null) {
            todo.setDone(done);
        }
        return todos.save(todo);
    }

    @Transactional
    public void delete(Caller caller, Long id) {
        todos.delete(writable(caller, id));
    }

    /**
     * A todo this caller may change, or 404. A moderator reads everyone but writes only its own, so it lands
     * on the owner-scoped lookup here just like a user does.
     *
     * <p>Someone else's id comes back "not found" rather than "forbidden", so neither answer says whether the
     * todo exists.
     */
    private Todo writable(Caller caller, Long id) {
        return (caller.writesEveryone() ? todos.findById(id) : todos.findByIdAndOwner(id, caller.accountId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static String cleanTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title is required");
        }
        return title.strip();
    }
}
