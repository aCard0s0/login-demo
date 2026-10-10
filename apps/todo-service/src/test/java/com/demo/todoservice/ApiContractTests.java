package com.demo.todoservice;

import com.demo.todoservice.todo.TodoService;
import com.demo.auth.client.Caller;
import com.demo.auth.client.JwtVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The shapes the /todos page relies on: who gets 401, who gets 403, and the {error} body on both. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/contract-test.db",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.hikari.maximum-pool-size=1",
})
@AutoConfigureMockMvc
class ApiContractTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    TodoService todos;

    /** The verifier is tested against a real JWKS in libs/auth-client; here it just maps two fixed tokens to two callers. */
    @MockitoBean
    JwtVerifier jwt;

    static final Caller OWNER = new Caller("1", "USER");
    /** The owner's own agent: same subject, pinned to one agent, good for 30 days. */
    static final Caller AGENT = new Caller("1", "AGENT", 5L);

    /** An agent's way in is MCP through agent-service, where its owner's READ/WRITE setting and activity log apply. */
    @Test
    void anAgentTokenIsRefusedOnEveryVerbAndTheOwnersStillWorks() throws Exception {
        when(jwt.callerOf(isNull())).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid or expired token"));
        when(jwt.callerOf("Bearer owner")).thenReturn(OWNER);
        when(jwt.callerOf("Bearer agent")).thenReturn(AGENT);
        long id = todos.add(OWNER, "keep me").getId();

        mvc.perform(get("/api/todos")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("invalid or expired token"));

        String refused = "an agent token can only connect to /mcp";
        mvc.perform(get("/api/todos").header("Authorization", "Bearer agent"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(refused));
        mvc.perform(post("/api/todos").header("Authorization", "Bearer agent").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"smuggled\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(refused));
        mvc.perform(put("/api/todos/" + id).header("Authorization", "Bearer agent")).andExpect(status().isForbidden());
        mvc.perform(patch("/api/todos/" + id).header("Authorization", "Bearer agent").contentType(MediaType.APPLICATION_JSON).content("{\"done\":true}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/todos/" + id).header("Authorization", "Bearer agent")).andExpect(status().isForbidden());
        assertEquals(1, todos.list(OWNER).size(), "nothing it tried may have stuck");
        assertEquals("keep me", todos.list(OWNER).get(0).getTitle());

        mvc.perform(get("/api/todos").header("Authorization", "Bearer owner")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("keep me"));
        mvc.perform(patch("/api/todos/" + id).header("Authorization", "Bearer owner").contentType(MediaType.APPLICATION_JSON).content("{\"done\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.done").value(true));
        mvc.perform(delete("/api/todos/" + id).header("Authorization", "Bearer owner")).andExpect(status().isOk());
        assertEquals(0, todos.list(OWNER).size());
        mvc.perform(get("/api/public/todos/stats")).andExpect(status().isOk()).andExpect(jsonPath("$.todos").isNumber());
    }
}
