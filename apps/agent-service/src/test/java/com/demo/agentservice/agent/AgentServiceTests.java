package com.demo.agentservice.agent;

import com.demo.agentservice.activity.Activity;
import com.demo.agentservice.activity.ActivityLog;
import com.demo.agentservice.token.Caller;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Inline rather than a test application.properties, which would shadow the main one instead of merging over it.
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/test.db",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        // SQLite allows a single writer; one connection keeps Hibernate from tripping over itself.
        "spring.datasource.hikari.maximum-pool-size=1",
})
class AgentServiceTests {

    @Autowired
    AgentService agents;

    @Autowired
    ActivityLog activity;

    /** Owners are account ids, so every test uses ids of its own and the order they run in cannot matter. */
    private static Caller user(String id) {
        return new Caller(id, "USER");
    }

    private static NewMcpServer server(String name, Access access) {
        return new NewMcpServer(name, "http://mcp.example/" + name, null, false, access);
    }

    @Test
    void oneOwnerCannotSeeOrTouchAnothersAgents() {
        Agent hers = agents.create(user("1"), new NewAgent("helper", "be helpful", null));
        Long server = hers.getServers().get(0).getId();

        assertTrue(agents.list(user("2")).isEmpty());
        assertThrows(ResponseStatusException.class, () -> agents.get(user("2"), hers.getId()));
        assertThrows(ResponseStatusException.class, () -> agents.update(user("2"), hers.getId(), new UpdateAgent("x", null, null), ""));
        assertThrows(ResponseStatusException.class, () -> agents.addServer(user("2"), hers.getId(), server("a", Access.READ), ""));
        assertThrows(ResponseStatusException.class, () -> agents.updateServer(user("2"), hers.getId(), server,
                new UpdateMcpServer(null, null, null, null, Access.WRITE), ""));
        assertThrows(ResponseStatusException.class, () -> agents.removeServer(user("2"), hers.getId(), server, ""));
        assertThrows(ResponseStatusException.class, () -> agents.delete(user("2"), hers.getId()));

        assertEquals(Access.READ, agents.get(user("1"), hers.getId()).getServers().get(0).getAccess(),
                "nothing the other account tried may have stuck");
        assertEquals(1, agents.list(user("1")).size());
    }

    @Test
    void aNewAgentStartsWithTheBuiltInTodoServerReadOnly() {
        Agent fresh = agents.create(user("3"), new NewAgent("  fresh  ", null, null));
        assertEquals("fresh", fresh.getName());
        assertEquals("", fresh.getInstructions());
        assertEquals(OthersAccess.NONE, fresh.getOthersAccess(), "no access to the owner's other agents until granted");
        assertEquals(1, fresh.getServers().size());
        AgentMcpServer todos = fresh.getServers().get(0);
        assertEquals("todos", todos.getName());
        assertEquals(Access.READ, todos.getAccess(), "an agent may look at the todos until the owner says it may change them");
        assertTrue(todos.isForwardCallerToken(), "the built-in server learns whose todos from the owner's own token");
        assertEquals("created with server 'todos' (READ)", activity.recent(fresh.getId()).get(0).getDetail());
    }

    @Test
    void everyChangeIsLoggedAndTheLogDiesWithTheAgent() {
        Agent a = agents.create(user("4"), new NewAgent("a", "", OthersAccess.NONE));
        Long todos = a.getServers().get(0).getId();

        agents.update(user("4"), a.getId(), new UpdateAgent(null, null, OthersAccess.READ), "");
        agents.update(user("4"), a.getId(), new UpdateAgent(null, null, OthersAccess.READ), "");  // no change, no line
        agents.updateServer(user("4"), a.getId(), todos, new UpdateMcpServer(null, null, null, null, Access.WRITE), "");
        AgentMcpServer extra = agents.addServer(user("4"), a.getId(), server("extra", Access.READ), "by agent 'b' (#9): ");
        agents.updateServer(user("4"), a.getId(), extra.getId(), new UpdateMcpServer(null, null, "Bearer s3cret", null, null), "");
        agents.removeServer(user("4"), a.getId(), extra.getId(), "");

        List<String> log = activity.recent(a.getId()).stream().map(Activity::getDetail).toList();
        assertEquals(List.of(
                "server 'extra' removed",
                "server 'extra' auth header set",
                "by agent 'b' (#9): server 'extra' added (READ)",
                "server 'todos' access READ -> WRITE",
                "othersAccess NONE -> READ",
                "created with server 'todos' (READ)"), log);
        assertTrue(activity.recent(a.getId()).stream().allMatch(l -> l.getKind().equals(Activity.CONFIG_CHANGED)));

        agents.delete(user("4"), a.getId());
        assertTrue(activity.recent(a.getId()).isEmpty(), "the history goes with the agent");
    }

    @Test
    void serverNamesAreShortLowerCaseAndUniquePerAgent() {
        Agent a = agents.create(user("5"), new NewAgent("a", "", null));
        assertThrows(ResponseStatusException.class, () -> agents.addServer(user("5"), a.getId(), server("todos", Access.READ), ""),
                "the built-in server already took that name");
        assertThrows(ResponseStatusException.class, () -> agents.addServer(user("5"), a.getId(), server("Has Spaces", Access.READ), ""));
        assertThrows(ResponseStatusException.class, () -> agents.addServer(user("5"), a.getId(), server("", Access.READ), ""));
        assertThrows(ResponseStatusException.class, () -> agents.addServer(user("5"), a.getId(),
                new NewMcpServer("ok", "ftp://nope", null, false, Access.READ), ""), "only http(s) servers");
        assertThrows(ResponseStatusException.class, () -> agents.addServer(user("5"), a.getId(),
                new NewMcpServer("ok", "http://mcp.example/", null, false, null), ""), "access is required");

        Agent b = agents.create(user("5"), new NewAgent("b", "", null));
        agents.addServer(user("5"), b.getId(), server("other", Access.WRITE), "");
        agents.addServer(user("5"), a.getId(), server("other", Access.WRITE), "");
        assertEquals(2, agents.get(user("5"), a.getId()).getServers().size(), "the same name on another agent is fine");
    }
}
