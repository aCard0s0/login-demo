package com.demo.todoservice.todo;

import com.demo.todoservice.token.Caller;
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

    /** Owners are account ids, so every test uses ids of its own and the order they run in cannot matter. */
    private static Caller user(String id) {
        return new Caller(id, "USER");
    }

    @Test
    void oneOwnerCannotSeeOrTouchAnothersTodos() {
        Todo hers = todos.add(user("1"), "buy milk");

        assertTrue(todos.list(user("2")).isEmpty());
        assertThrows(ResponseStatusException.class, () -> todos.toggle(user("2"), hers.getId()));
        assertThrows(ResponseStatusException.class, () -> todos.delete(user("2"), hers.getId()));

        assertEquals(1, todos.list(user("1")).size());
        assertTrue(todos.toggle(user("1"), hers.getId()).isDone());
        todos.delete(user("1"), hers.getId());
        assertTrue(todos.list(user("1")).isEmpty());
    }

    @Test
    void anEditChangesOnlyTheFieldsItWasGiven() {
        Todo mine = todos.add(user("3"), "  buy milk  ");
        assertEquals("buy milk", mine.getTitle(), "a title is stripped on the way in");

        assertThrows(ResponseStatusException.class, () -> todos.update(user("4"), mine.getId(), "buy beer", null),
                "another account's todo must not be editable");
        assertThrows(ResponseStatusException.class, () -> todos.update(user("3"), 999_999L, "ghost", null));
        assertThrows(ResponseStatusException.class, () -> todos.update(user("3"), mine.getId(), "   ", null),
                "a blank title is rejected rather than saved");

        assertEquals("buy bread", todos.update(user("3"), mine.getId(), " buy bread ", null).getTitle());
        assertFalse(todos.update(user("3"), mine.getId(), "buy bread", null).isDone(), "a null done must not flip it");

        Todo done = todos.update(user("3"), mine.getId(), null, true);
        assertTrue(done.isDone());
        assertEquals("buy bread", done.getTitle(), "a null title must not clear the title");
    }

    @Test
    void aModeratorReadsEveryoneButStillWritesOnlyItsOwn() {
        Caller moderator = new Caller("5", "MODERATOR");
        Todo theirs = todos.add(user("6"), "not the moderator's");
        Todo own = todos.add(moderator, "the moderator's own");

        assertTrue(todos.list(moderator).stream().anyMatch(t -> t.getId().equals(theirs.getId())),
                "a moderator reads everyone's todos");
        assertTrue(todos.list(user("6")).stream().noneMatch(t -> t.getId().equals(own.getId())),
                "a user still reads only its own");

        assertThrows(ResponseStatusException.class, () -> todos.update(moderator, theirs.getId(), "edited", null),
                "reading everyone must not have handed over writing everyone");
        assertThrows(ResponseStatusException.class, () -> todos.delete(moderator, theirs.getId()));

        assertEquals("edited", todos.update(moderator, own.getId(), "edited", null).getTitle());
    }

    @Test
    void anAdminWritesAnybodysTodo() {
        Caller admin = new Caller("7", "ADMIN");
        Todo theirs = todos.add(user("8"), "somebody else's");

        assertTrue(todos.list(admin).stream().anyMatch(t -> t.getId().equals(theirs.getId())));
        assertEquals("edited by the admin", todos.update(admin, theirs.getId(), "edited by the admin", null).getTitle());
        assertTrue(todos.toggle(admin, theirs.getId()).isDone());

        todos.delete(admin, theirs.getId());
        assertTrue(todos.list(user("8")).isEmpty());
    }

    @Test
    void anUnknownRoleGetsNoMoreThanAUser() {
        Caller stranger = new Caller("9", "SUPERUSER");
        Todo theirs = todos.add(user("10"), "not the stranger's");

        assertTrue(todos.list(stranger).isEmpty(), "an unrecognised role must land on least privilege");
        assertThrows(ResponseStatusException.class, () -> todos.update(stranger, theirs.getId(), "edited", null));
    }
}
