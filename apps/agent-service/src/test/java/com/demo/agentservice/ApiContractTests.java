package com.demo.agentservice;

import com.demo.agentservice.agent.AgentService;
import com.demo.agentservice.agent.NewAgent;
import com.demo.agentservice.token.AgentTokens;
import com.demo.token.Caller;
import com.demo.token.JwtVerifier;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The shapes the frontend relies on: who gets 401, who gets 404, and what a token request answers. */
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
    AgentService agents;

    /** The verifier is unit-tested against a real JWKS; here it just maps two fixed tokens to two accounts. */
    @MockitoBean
    JwtVerifier jwt;

    /** auth-service's minting is its own contract test; here it just hands back a fixed string. */
    @MockitoBean
    AgentTokens tokens;

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
                .andExpect(jsonPath("$[0].servers[0].hasAuthHeader").value(false))
                .andExpect(jsonPath("$[0].servers[0].trusted").value(true))
                .andExpect(jsonPath("$[0].servers[0].readOnlyTools").isEmpty());
        // Even an admin: agents are strictly the owner's.
        mvc.perform(get("/api/agents/" + hers).header("Authorization", "Bearer two")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("no such agent"));
        mvc.perform(get("/api/agents/" + hers + "/activity").header("Authorization", "Bearer two")).andExpect(status().isNotFound());
        mvc.perform(post("/api/agents/" + hers + "/token").header("Authorization", "Bearer two")).andExpect(status().isNotFound());
        // The owner gets a token, minted by auth-service, and the agent's log says so.
        when(tokens.issue("1", hers)).thenReturn("agent.jwt.here");
        mvc.perform(post("/api/agents/" + hers + "/token").header("Authorization", "Bearer one")).andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("agent.jwt.here"));
        mvc.perform(get("/api/agents/" + hers + "/activity").header("Authorization", "Bearer one")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].kind").value("config_changed"))
                .andExpect(jsonPath("$[0].detail").value("agent token issued"));
        // Revoking is the owner's alone too, goes to auth-service for this one agent, and is in the log.
        mvc.perform(post("/api/agents/" + hers + "/token/revoke").header("Authorization", "Bearer two")).andExpect(status().isNotFound());
        verify(tokens, never()).revoke(any());
        mvc.perform(post("/api/agents/" + hers + "/token/revoke").header("Authorization", "Bearer one")).andExpect(status().isOk());
        verify(tokens).revoke(hers);
        mvc.perform(get("/api/agents/" + hers + "/activity").header("Authorization", "Bearer one")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].detail").value("agent tokens revoked"));
        mvc.perform(get("/api/public/agents/stats")).andExpect(status().isOk()).andExpect(jsonPath("$.agents").isNumber());
    }

    /** The token an agent connects with opens /mcp and nothing else: here it could widen its own access or mint more tokens. */
    @Test
    void anAgentTokenIsRefusedOnTheRestApiEvenForItsOwnAgent() throws Exception {
        Long mine = agents.create(new Caller("3", "USER"), new NewAgent("mine", "", null)).getId();
        Long todos = agents.get(new Caller("3", "USER"), mine).getServers().get(0).getId();
        when(jwt.callerOf("Bearer agent")).thenReturn(new Caller("3", "AGENT", mine));

        mvc.perform(get("/api/agents").header("Authorization", "Bearer agent")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("an agent token can only connect to /mcp"));
        mvc.perform(patch("/api/agents/" + mine + "/servers/" + todos).header("Authorization", "Bearer agent")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"access\":\"WRITE\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/agents/" + mine + "/token").header("Authorization", "Bearer agent")).andExpect(status().isForbidden());
        mvc.perform(post("/api/agents/" + mine + "/token/revoke").header("Authorization", "Bearer agent")).andExpect(status().isForbidden());
        verify(tokens, never()).revoke(any());
        assertEquals("READ", agents.get(new Caller("3", "USER"), mine).getServers().get(0).getAccess().name(), "nothing it tried may have stuck");
    }
}
