package com.demo.todoservice.todo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Inline rather than a test application.properties, which would shadow the main one instead of merging over it.
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/test.db",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        // SQLite allows a single writer; one connection keeps Hibernate from tripping over itself.
        "spring.datasource.hikari.maximum-pool-size=1",
})
class TodoServiceTests {

    @Autowired
    TodoService todos;

    @Test
    void oneOwnerCannotSeeOrTouchAnothersTodos() {
        // Owners are account ids now, not emails, so changing an email cannot orphan a list.
        Todo hers = todos.add("1", "buy milk");

        assertTrue(todos.list("2").isEmpty());
        assertThrows(ResponseStatusException.class, () -> todos.toggle("2", hers.getId()));
        assertThrows(ResponseStatusException.class, () -> todos.delete("2", hers.getId()));

        assertEquals(1, todos.list("1").size());
        assertTrue(todos.toggle("1", hers.getId()).isDone());
        todos.delete("1", hers.getId());
        assertTrue(todos.list("1").isEmpty());
    }

    @Test
    void anEditChangesOnlyTheFieldsItWasGiven() {
        // Owners of their own, so the ordering between test methods cannot matter.
        Todo mine = todos.add("3", "  buy milk  ");
        assertEquals("buy milk", mine.getTitle(), "a title is stripped on the way in");

        assertThrows(ResponseStatusException.class, () -> todos.update("4", mine.getId(), "buy beer", null),
                "another account's todo must not be editable");
        assertThrows(ResponseStatusException.class, () -> todos.update("3", 999_999L, "ghost", null));
        assertThrows(ResponseStatusException.class, () -> todos.update("3", mine.getId(), "   ", null),
                "a blank title is rejected rather than saved");

        assertEquals("buy bread", todos.update("3", mine.getId(), " buy bread ", null).getTitle());
        assertFalse(todos.update("3", mine.getId(), "buy bread", null).isDone(), "a null done must not flip it");

        Todo done = todos.update("3", mine.getId(), null, true);
        assertTrue(done.isDone());
        assertEquals("buy bread", done.getTitle(), "a null title must not clear the title");
    }
}
