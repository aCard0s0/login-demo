package com.demo.agentservice.agent;

import com.demo.agentservice.activity.Activity;
import com.demo.agentservice.activity.ActivityLog;
import com.demo.agentservice.agent.dto.*;
import com.demo.agentservice.agent.entities.Access;
import com.demo.agentservice.agent.entities.OthersAccess;
import com.demo.agentservice.agent.entities.Agent;
import com.demo.agentservice.agent.entities.AgentMcpServer;
import com.demo.auth.client.Caller;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetAddress;
import java.util.List;

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
class AgentServiceTests {

    /** The *.example servers below are public as far as the URL policy is concerned, without touching DNS. */
    @TestConfiguration
    static class FakeDns {
        @Bean
        @Primary
        ServerUrls serverUrls(Environment env) {
            return new ServerUrls(env, host -> new InetAddress[] {InetAddress.getByName(host.endsWith(".example") ? "93.184.216.34" : host)});
        }
    }

    @Autowired
    AgentService agents;

    @Autowired
    ActivityLog activity;

    @Autowired
    JdbcClient db;

    @Autowired
    AuthHeaderMigration migration;

    /** Owners are user ids, so every test uses ids of its own and the order they run in cannot matter. */
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
                "nothing the other user tried may have stuck");
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
    void anAuthHeaderIsEncryptedOnDiskAndPlainToTheCode() {
        Agent a = agents.create(user("11"), new NewAgent("a", "", null));
        AgentMcpServer s = agents.addServer(user("11"), a.getId(), new NewMcpServer("secret", "http://mcp.example/s", "Bearer s3cret", false, Access.READ), "");

        String stored = db.sql("SELECT auth_header FROM agent_mcp_servers WHERE id = ?").param(s.getId()).query(String.class).single();
        assertTrue(stored.startsWith("v1:"), stored);
        assertFalse(stored.contains("s3cret"), "the secret must not be readable in the row");
        assertEquals("Bearer s3cret", header(a, s), "and plain once loaded");

        agents.updateServer(user("11"), a.getId(), s.getId(), new UpdateMcpServer(null, null, "Bearer other", null, null), "");
        String again = db.sql("SELECT auth_header FROM agent_mcp_servers WHERE id = ?").param(s.getId()).query(String.class).single();
        assertTrue(again.startsWith("v1:") && !again.equals(stored));
        assertEquals("Bearer other", header(a, s));

        // A row from before encryption existed is read as it is, and the startup migration rewrites it.
        db.sql("UPDATE agent_mcp_servers SET auth_header = 'Bearer legacy' WHERE id = ?").param(s.getId()).update();
        assertEquals("Bearer legacy", header(a, s));
        assertEquals(1, migration.migrate());
        assertTrue(db.sql("SELECT auth_header FROM agent_mcp_servers WHERE id = ?").param(s.getId()).query(String.class).single().startsWith("v1:"));
        assertEquals("Bearer legacy", header(a, s));
        assertEquals(0, migration.migrate(), "nothing left to migrate: it is idempotent");
    }

    /** The server's header as the code sees it, re-read from the database. */
    private String header(Agent a, AgentMcpServer s) {
        return agents.get(user("11"), a.getId()).getServers().stream().filter(x -> x.getId().equals(s.getId())).findFirst().orElseThrow().getAuthHeader();
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
        // The URL policy is applied on the way in, on add and on edit: nothing private is ever saved.
        assertEquals("url is refused: '10.0.0.1' resolves to 10.0.0.1, a loopback, private, link-local, carrier-grade NAT or multicast address",
                assertThrows(ResponseStatusException.class, () -> agents.addServer(user("5"), a.getId(),
                        new NewMcpServer("ok", "http://10.0.0.1/mcp", null, false, Access.READ), "")).getReason());
        assertThrows(ResponseStatusException.class, () -> agents.updateServer(user("5"), a.getId(), a.getServers().get(0).getId(),
                new UpdateMcpServer(null, "http://auth-service:9081/internal/agent-tokens", null, null, null), ""));
        assertEquals(1, agents.get(user("5"), a.getId()).getServers().size());
        assertEquals("http://localhost:9082/mcp", agents.get(user("5"), a.getId()).getServers().get(0).getUrl(), "the edit must not have stuck");
        assertThrows(ResponseStatusException.class, () -> agents.addServer(user("5"), a.getId(),
                new NewMcpServer("ok", "http://mcp.example/", null, false, null), ""), "access is required");

        Agent b = agents.create(user("5"), new NewAgent("b", "", null));
        agents.addServer(user("5"), b.getId(), server("other", Access.WRITE), "");
        agents.addServer(user("5"), a.getId(), server("other", Access.WRITE), "");
        assertEquals(2, agents.get(user("5"), a.getId()).getServers().size(), "the same name on another agent is fine");
    }

    /** The caller's token is the owner's whole authority, so it never goes to a URL the owner typed in -- only to the deployment's own servers. */
    @Test
    void theCallersTokenIsForwardedOnlyToTheDeploymentsOwnServers() {
        Agent a = agents.create(user("12"), new NewAgent("a", "", null));
        Long todos = a.getServers().get(0).getId();

        String why = assertThrows(ResponseStatusException.class, () -> agents.addServer(user("12"), a.getId(),
                new NewMcpServer("mine", "http://mcp.example/mine", null, true, Access.READ), "")).getReason();
        assertTrue(why.startsWith("the caller's token is only forwarded"), why);
        AgentMcpServer mine = agents.addServer(user("12"), a.getId(), new NewMcpServer("mine", "http://mcp.example/mine", "Bearer own", false, Access.READ), "");
        assertThrows(ResponseStatusException.class, () -> agents.updateServer(user("12"), a.getId(), mine.getId(),
                new UpdateMcpServer(null, null, null, true, null), ""), "nor may the forward be switched on later");
        // The other way round: the built-in server forwards, so it cannot be pointed at an untrusted URL with the forward still on.
        assertThrows(ResponseStatusException.class, () -> agents.updateServer(user("12"), a.getId(), todos,
                new UpdateMcpServer(null, "http://mcp.example/elsewhere", null, null, null), ""));
        agents.updateServer(user("12"), a.getId(), todos, new UpdateMcpServer(null, "http://mcp.example/elsewhere", null, false, null), "");
        assertFalse(agents.get(user("12"), a.getId()).getServers().get(0).isForwardCallerToken(), "both in one edit is fine");

        // Column widths are refused here, not by the database.
        assertThrows(ResponseStatusException.class, () -> agents.addServer(user("12"), a.getId(),
                new NewMcpServer("long", "http://mcp.example/" + "x".repeat(250), null, false, Access.READ), ""));
        assertThrows(ResponseStatusException.class, () -> agents.addServer(user("12"), a.getId(),
                new NewMcpServer("hdr", "http://mcp.example/hdr", "Bearer " + "x".repeat(1400), false, Access.READ), ""));
        assertThrows(ResponseStatusException.class, () -> agents.update(user("12"), a.getId(), new UpdateAgent(null, "x".repeat(8001), null), ""));
        assertEquals(2, agents.get(user("12"), a.getId()).getServers().size(), "none of the refused rows stuck");
    }
}
