package com.demo.accountservice;

import com.demo.accountservice.account.dto.Access;
import com.demo.accountservice.account.AccountService;
import com.demo.accountservice.account.dto.NewAccount;
import com.demo.token.Caller;
import com.demo.token.JwtVerifier;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The shapes the /bank page relies on: who gets 401, 403 and 404, what a body that does not parse gets, and the {error} body on all of them. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/contract-test.db",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.hikari.maximum-pool-size=1",
})
@AutoConfigureMockMvc
class ApiContractTests {

    static final String BASE = "/api/bank/accounts";

    @Autowired
    MockMvc mvc;

    @Autowired
    AccountService bank;

    /** The verifier is tested against a real JWKS in JwtVerifierTests; here it just maps fixed tokens to callers. */
    @MockitoBean
    JwtVerifier jwt;

    /** One owner per test: they share a database, and each lists its own accounts. */
    static final Caller OWNER = new Caller("50", "USER");
    static final Caller GRANTER = new Caller("54", "USER");
    static final Caller PICKY = new Caller("55", "USER");
    static final Caller STRANGER = new Caller("51", "USER");
    static final Caller MODERATOR = new Caller("52", "MODERATOR");
    /** Somebody else's agent: a grant may go to any agent. */
    static final Caller HELPER = new Caller("53", "AGENT", 22L);

    @BeforeEach
    void tokens() {
        when(jwt.callerOf(isNull())).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid or expired token"));
        when(jwt.callerOf("Bearer owner")).thenReturn(OWNER);
        when(jwt.callerOf("Bearer granter")).thenReturn(GRANTER);
        when(jwt.callerOf("Bearer picky")).thenReturn(PICKY);
        when(jwt.callerOf("Bearer stranger")).thenReturn(STRANGER);
        when(jwt.callerOf("Bearer moderator")).thenReturn(MODERATOR);
        when(jwt.callerOf("Bearer helper")).thenReturn(HELPER);
    }

    @Test
    void anOwnerOpensFundsAndMovesMoneyOverHttp() throws Exception {
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("invalid or expired token"));
        mvc.perform(get("/api/public/bank/stats")).andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts").isNumber()).andExpect(jsonPath("$.transfers").isNumber());

        long savings = id(call(post(BASE), "owner", "{\"name\":\"  savings  \"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("savings"))
                .andExpect(jsonPath("$.owner").value("50"))
                .andExpect(jsonPath("$.balance").value(0))
                .andExpect(jsonPath("$.permissions").isEmpty()));
        long bot = id(call(post(BASE), "owner", "{\"name\":\"bot\",\"agentId\":21}").andExpect(status().isOk())
                .andExpect(jsonPath("$.agentId").value(21)));

        call(post(BASE + "/" + savings + "/deposit"), "owner", "{\"amount\":1000}").andExpect(status().isOk())
                .andExpect(jsonPath("$.to").value(savings))
                .andExpect(jsonPath("$.amount").value(1000))
                .andExpect(jsonPath("$.by").value("user 50"));
        call(post(BASE + "/" + savings + "/transfers"), "owner", "{\"to\":" + bot + ",\"amount\":300}").andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value(savings))
                .andExpect(jsonPath("$.to").value(bot));
        call(post(BASE + "/" + savings + "/transfers"), "owner", "{\"to\":" + bot + ",\"amount\":5000}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("insufficient funds"));

        call(get(BASE + "/" + savings), "owner", null).andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(700));
        call(get(BASE + "/" + bot), "owner", null).andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(300));
        call(get(BASE + "/" + savings + "/transfers"), "owner", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].amount").value(300))
                .andExpect(jsonPath("$[1].from").doesNotExist());
        call(get(BASE), "owner", null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));

        // Someone else's account is not found, not forbidden: nothing to learn about whether it exists.
        call(get(BASE), "stranger", null).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        call(get(BASE + "/" + savings), "stranger", null).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("no such account"));
        call(get(BASE + "/" + savings + "/transfers"), "stranger", null).andExpect(status().isNotFound());
        call(post(BASE + "/" + savings + "/transfers"), "stranger", "{\"to\":" + bot + ",\"amount\":1}").andExpect(status().isNotFound());
        call(post(BASE + "/" + savings + "/deposit"), "stranger", "{\"amount\":1}").andExpect(status().isNotFound());

        call(get(BASE + "/" + savings), "moderator", null).andExpect(status().isOk());
        call(post(BASE + "/" + savings + "/transfers"), "moderator", "{\"to\":" + bot + ",\"amount\":1}").andExpect(status().isNotFound());

        assertEquals(700, bank.get(OWNER, savings).getBalance(), "none of the refusals may have moved money");
    }

    @Test
    void grantsAreSetAndRemovedOverHttpByTheOwnerOnly() throws Exception {
        long shared = bank.create(GRANTER, new NewAccount("household", null)).getId();
        long bots = bank.create(GRANTER, new NewAccount("bot", 21L)).getId();
        String permission = BASE + "/" + shared + "/permissions/22";

        call(put(permission), "granter", "{\"access\":\"READ\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions.length()").value(1))
                .andExpect(jsonPath("$.permissions[0].agentId").value(22))
                .andExpect(jsonPath("$.permissions[0].access").value("READ"));
        assertEquals(shared, bank.get(HELPER, shared).getId(), "READ lets the agent see it");

        call(put(permission), "granter", "{\"access\":\"WRITE\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions.length()").value(1))
                .andExpect(jsonPath("$.permissions[0].access").value("WRITE"));

        call(put(permission), "stranger", "{\"access\":\"READ\"}").andExpect(status().isNotFound());
        call(put(BASE + "/" + bots + "/permissions/21"), "granter", "{\"access\":\"READ\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("that agent already owns this account"));
        // An agent never widens its own reach, whatever it was granted.
        call(put(permission), "helper", "{\"access\":\"WRITE\"}").andExpect(status().isForbidden());
        call(post(BASE + "/" + shared + "/deposit"), "helper", "{\"amount\":1}").andExpect(status().isForbidden());
        call(post(BASE), "helper", "{\"name\":\"mine now\"}").andExpect(status().isForbidden());

        call(delete(permission), "stranger", null).andExpect(status().isNotFound());
        call(delete(permission), "granter", null).andExpect(status().isOk()).andExpect(jsonPath("$.permissions").isEmpty());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> bank.get(HELPER, shared)).getStatusCode().value(),
                "a removed grant bites at once");
        call(delete(permission), "granter", null).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("no such permission"));
    }

    /** An agent's way in is MCP through agent-service, where its owner's READ/WRITE setting and activity log apply. */
    @Test
    void anAgentTokenIsRefusedOnTheRestApiEvenWithAWriteGrant() throws Exception {
        long shared = bank.create(GRANTER, new NewAccount("pocket money", null)).getId();
        long elsewhere = bank.create(STRANGER, new NewAccount("shop", null)).getId();
        bank.deposit(GRANTER, shared, 100L);
        bank.setPermission(GRANTER, shared, HELPER.agentId(), Access.WRITE);

        call(get(BASE), "helper", null).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("an agent token can only reach the accounts over MCP"));
        call(get(BASE + "/" + shared), "helper", null).andExpect(status().isForbidden());
        call(get(BASE + "/" + shared + "/transfers"), "helper", null).andExpect(status().isForbidden());
        call(post(BASE + "/" + shared + "/transfers"), "helper", "{\"to\":" + elsewhere + ",\"amount\":10}").andExpect(status().isForbidden());
        assertEquals(100, bank.get(GRANTER, shared).getBalance(), "nothing may have moved");
    }

    @Test
    void aBodyThatDoesNotFitIs400InTheSameShape() throws Exception {
        long id = bank.create(PICKY, new NewAccount("mine", null)).getId();

        call(post(BASE), "picky", "{\"name\":\"   \"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("name is required"));
        call(post(BASE), "picky", "{\"name\":\"" + "x".repeat(101) + "\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("name is too long"));
        call(post(BASE + "/" + id + "/deposit"), "picky", "{\"amount\":\"lots\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isString());
        call(post(BASE + "/" + id + "/deposit"), "picky", "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("amount must be a positive number of cents"));
        call(post(BASE + "/" + id + "/deposit"), "picky", "{\"amount\":1000000000000001}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("amount is too large"));
        call(post(BASE + "/" + id + "/transfers"), "picky", "{\"amount\":1}").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("no such destination account"));
        call(put(BASE + "/" + id + "/permissions/22"), "picky", "{\"access\":\"OWNER\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isString());
        call(put(BASE + "/" + id + "/permissions/22"), "picky", "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("access must be READ or WRITE"));

        assertEquals(0, bank.get(PICKY, id).getBalance());
        assertEquals(1, bank.list(PICKY).size(), "no stray account was opened");
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String token, String body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }

    private static long id(ResultActions result) throws Exception {
        return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }
}
