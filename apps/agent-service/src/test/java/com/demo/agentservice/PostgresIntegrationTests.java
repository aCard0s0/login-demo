package com.demo.agentservice;

import com.demo.agentservice.activity.Activity;
import com.demo.agentservice.activity.ActivityLog;
import com.demo.agentservice.agent.AgentService;
import com.demo.agentservice.agent.AuthHeaderMigration;
import com.demo.agentservice.agent.entities.Access;
import com.demo.agentservice.agent.entities.Agent;
import com.demo.agentservice.agent.entities.AgentMcpServer;
import com.demo.agentservice.agent.dto.NewAgent;
import com.demo.agentservice.agent.dto.NewMcpServer;
import com.demo.auth.client.Caller;
import com.demo.auth.client.JwtVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The real database, in a throwaway container: the Flyway migrations build the schema, Hibernate validates the
 * entities against it (the context would not start otherwise), and the queries the SQLite tests cannot vouch
 * for -- the bulk delete, the index, a legacy row -- run against Postgres. Skipped, not failed, without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class PostgresIntegrationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @MockitoBean
    JwtVerifier jwt;

    @Autowired
    AgentService agents;

    @Autowired
    ActivityLog activity;

    @Autowired
    AuthHeaderMigration migration;

    @Autowired
    JdbcClient db;

    @Autowired
    MockMvc mvc;

    static final Caller OWNER = new Caller("1", "USER");

    @Test
    void theMigrationsRanAndLeftTheIndexTheLogQueryWalks() {
        List<String> applied = db.sql("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank").query(String.class).list();
        assertEquals(List.of("1", "2"), applied);
        List<String> indexes = db.sql("SELECT indexdef FROM pg_indexes WHERE tablename = 'agent_activity'").query(String.class).list();
        assertTrue(indexes.stream().anyMatch(d -> d.endsWith("(agent_id, id)")), indexes.toString());
    }

    @Test
    void deletingAnAgentTakesItsWholeLogWithIt() {
        Agent a = agents.create(OWNER, new NewAgent("doomed", "", null));
        for (int i = 0; i < 150; i++) {
            activity.record(a.getId(), Activity.TOOL_CALL, "call " + i);
        }
        assertEquals(100, activity.recent(a.getId()).size(), "newest hundred");
        assertEquals("call 149", activity.recent(a.getId()).get(0).getDetail());

        agents.delete(OWNER, a.getId());
        assertEquals(0, db.sql("SELECT count(*) FROM agent_activity WHERE agent_id = ?").param(a.getId()).query(Long.class).single());
        assertEquals(0, db.sql("SELECT count(*) FROM agent_mcp_servers WHERE agent_id = ?").param(a.getId()).query(Long.class).single());
    }

    @Test
    void anAuthHeaderIsEncryptedOnPostgresAndALegacyRowIsCaughtUp() {
        Agent a = agents.create(OWNER, new NewAgent("keeper", "", null));
        AgentMcpServer s = agents.addServer(OWNER, a.getId(), new NewMcpServer("secret", "http://localhost:9084/mcp", "Bearer s3cret", false, Access.READ), "");
        String stored = db.sql("SELECT auth_header FROM agent_mcp_servers WHERE id = ?").param(s.getId()).query(String.class).single();
        assertTrue(stored.startsWith("v1:"), stored);
        assertFalse(stored.contains("s3cret"));

        db.sql("UPDATE agent_mcp_servers SET auth_header = 'Bearer legacy' WHERE id = ?").param(s.getId()).update();
        assertEquals(1, migration.migrate());
        assertEquals("Bearer legacy", agents.get(OWNER, a.getId()).getServers().stream()
                .filter(x -> x.getId().equals(s.getId())).findFirst().orElseThrow().getAuthHeader());
    }

    @Test
    void theApiAnswersOnPostgresAndTheHealthcheckIsUp() throws Exception {
        when(jwt.callerOf("Bearer one")).thenReturn(OWNER);
        agents.create(OWNER, new NewAgent("listed", "", null));
        mvc.perform(get("/api/agents").header("Authorization", "Bearer one")).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'listed')].servers[0].name").value("todos"));
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }
}
