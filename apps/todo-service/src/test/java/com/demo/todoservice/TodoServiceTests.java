package com.demo.todoservice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Inline rather than a test application.properties, which would shadow the main one instead of merging over it.
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/test.db",
        "spring.jpa.hibernate.ddl-auto=create-drop",
})
class TodoServiceTests {

    @Autowired
    TodoService todos;

    @Test
    void oneOwnerCannotSeeOrTouchAnothersTodos() {
        Todo hers = todos.add("alice@example.com", "buy milk");

        assertTrue(todos.list("bob@example.com").isEmpty());
        assertThrows(ResponseStatusException.class, () -> todos.toggle("bob@example.com", hers.getId()));
        assertThrows(ResponseStatusException.class, () -> todos.delete("bob@example.com", hers.getId()));

        assertEquals(1, todos.list("alice@example.com").size());
        assertTrue(todos.toggle("alice@example.com", hers.getId()).isDone());
        todos.delete("alice@example.com", hers.getId());
        assertTrue(todos.list("alice@example.com").isEmpty());
    }
}
