package com.demo.agentservice;

import com.demo.agentservice.agent.AgentService;
import com.demo.agentservice.agent.NewAgent;
import com.demo.agentservice.token.Caller;
import com.demo.agentservice.token.JwtVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The shapes the frontend relies on: who gets 401, who gets 404, and what Run says with no key. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/contract-test.db",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.hikari.maximum-pool-size=1",
        "anthropic.api-key=",
})
@AutoConfigureMockMvc
class ApiContractTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    AgentService agents;

    /** The verifier is unit-tested against a real JWKS; here it just maps two fixed tokens to two accounts. */
    @MockitoBean
    JwtVerifier jwt;

    @Test
    void noTokenIs401AndSomeoneElsesAgentIs404() throws Exception {
        when(jwt.callerOf(isNull())).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid or expired token"));
        when(jwt.callerOf("Bearer one")).thenReturn(new Caller("1", "USER"));
        when(jwt.callerOf("Bearer two")).thenReturn(new Caller("2", "ADMIN"));
        Long hers = agents.create(new Caller("1", "USER"), new NewAgent("hers", "", null)).getId();

        mvc.perform(get("/api/agents")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid or expired token"));
        mvc.perform(get("/api/agents").header("Authorization", "Bearer one")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("hers"))
                .andExpect(jsonPath("$[0].servers[0].name").value("todos"))
                .andExpect(jsonPath("$[0].servers[0].hasAuthHeader").value(false));
        // Even an admin: agents are strictly the owner's.
        mvc.perform(get("/api/agents/" + hers).header("Authorization", "Bearer two")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("no such agent"));
        mvc.perform(get("/api/agents/" + hers + "/activity").header("Authorization", "Bearer two")).andExpect(status().isNotFound());
        mvc.perform(post("/api/agents/" + hers + "/run").header("Authorization", "Bearer two")
                        .contentType("application/json").content("{\"prompt\":\"hi\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("ANTHROPIC_API_KEY is not configured"));
        mvc.perform(get("/api/public/agents/stats")).andExpect(status().isOk()).andExpect(jsonPath("$.agents").isNumber());
    }
}
