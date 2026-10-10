package com.demo.walletservice;

import com.demo.walletservice.wallet.dto.Access;
import com.demo.walletservice.wallet.WalletService;
import com.demo.walletservice.wallet.dto.NewWallet;
import com.demo.auth.client.Caller;
import com.demo.auth.client.JwtVerifier;
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

/** The shapes the /wallets page relies on: who gets 401, 403 and 404, what a body that does not parse gets, and the {error} body on all of them. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/contract-test.db",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.hikari.maximum-pool-size=1",
})
@AutoConfigureMockMvc
class ApiContractTests {

    static final String BASE = "/api/wallets";

    @Autowired
    MockMvc mvc;

    @Autowired
    WalletService service;

    /** The verifier is tested against a real JWKS in JwtVerifierTests; here it just maps fixed tokens to callers. */
    @MockitoBean
    JwtVerifier jwt;

    /** One owner per test: they share a database, and each lists its own wallets. */
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
        mvc.perform(get("/api/public/wallets/stats")).andExpect(status().isOk())
                .andExpect(jsonPath("$.wallets").isNumber()).andExpect(jsonPath("$.transfers").isNumber());

        long savings = id(call(post(BASE), "owner", "{\"name\":\"  savings  \"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("savings"))
                .andExpect(jsonPath("$.owner").value("50"))
                .andExpect(jsonPath("$.balance").value(0))
                .andExpect(jsonPath("$.grants").isEmpty()));
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

        // Someone else's wallet is not found, not forbidden: nothing to learn about whether it exists.
        call(get(BASE), "stranger", null).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        call(get(BASE + "/" + savings), "stranger", null).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("no such wallet"));
        call(get(BASE + "/" + savings + "/transfers"), "stranger", null).andExpect(status().isNotFound());
        call(post(BASE + "/" + savings + "/transfers"), "stranger", "{\"to\":" + bot + ",\"amount\":1}").andExpect(status().isNotFound());
        call(post(BASE + "/" + savings + "/deposit"), "stranger", "{\"amount\":1}").andExpect(status().isNotFound());

        call(get(BASE + "/" + savings), "moderator", null).andExpect(status().isOk());
        call(post(BASE + "/" + savings + "/transfers"), "moderator", "{\"to\":" + bot + ",\"amount\":1}").andExpect(status().isNotFound());

        assertEquals(700, service.get(OWNER, savings).getBalance(), "none of the refusals may have moved money");
    }

    @Test
    void grantsAreSetAndRemovedOverHttpByTheOwnerOnly() throws Exception {
        long shared = service.create(GRANTER, new NewWallet("household", null)).getId();
        long bots = service.create(GRANTER, new NewWallet("bot", 21L)).getId();
        String grant = BASE + "/" + shared + "/grants/22";

        call(put(grant), "granter", "{\"access\":\"READ\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.grants.length()").value(1))
                .andExpect(jsonPath("$.grants[0].agentId").value(22))
                .andExpect(jsonPath("$.grants[0].access").value("READ"));
        assertEquals(shared, service.get(HELPER, shared).getId(), "READ lets the agent see it");

        call(put(grant), "granter", "{\"access\":\"WRITE\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.grants.length()").value(1))
                .andExpect(jsonPath("$.grants[0].access").value("WRITE"));

        call(put(grant), "stranger", "{\"access\":\"READ\"}").andExpect(status().isNotFound());
        call(put(BASE + "/" + bots + "/grants/21"), "granter", "{\"access\":\"READ\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("that agent already owns this wallet"));
        // An agent never widens its own reach, whatever it was granted.
        call(put(grant), "helper", "{\"access\":\"WRITE\"}").andExpect(status().isForbidden());
        call(post(BASE + "/" + shared + "/deposit"), "helper", "{\"amount\":1}").andExpect(status().isForbidden());
        call(post(BASE), "helper", "{\"name\":\"mine now\"}").andExpect(status().isForbidden());

        call(delete(grant), "stranger", null).andExpect(status().isNotFound());
        call(delete(grant), "granter", null).andExpect(status().isOk()).andExpect(jsonPath("$.grants").isEmpty());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.get(HELPER, shared)).getStatusCode().value(),
                "a removed grant bites at once");
        call(delete(grant), "granter", null).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("no such grant"));
    }

    /** An agent's way in is MCP through agent-service, where its owner's READ/WRITE setting and activity log apply. */
    @Test
    void anAgentTokenIsRefusedOnTheRestApiEvenWithAWriteGrant() throws Exception {
        long shared = service.create(GRANTER, new NewWallet("pocket money", null)).getId();
        long elsewhere = service.create(STRANGER, new NewWallet("shop", null)).getId();
        service.deposit(GRANTER, shared, 100L);
        service.setGrant(GRANTER, shared, HELPER.agentId(), Access.WRITE);

        call(get(BASE), "helper", null).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("an agent token can only connect to /mcp"));
        call(get(BASE + "/" + shared), "helper", null).andExpect(status().isForbidden());
        call(get(BASE + "/" + shared + "/transfers"), "helper", null).andExpect(status().isForbidden());
        call(post(BASE + "/" + shared + "/transfers"), "helper", "{\"to\":" + elsewhere + ",\"amount\":10}").andExpect(status().isForbidden());
        assertEquals(100, service.get(GRANTER, shared).getBalance(), "nothing may have moved");
    }

    @Test
    void aBodyThatDoesNotFitIs400InTheSameShape() throws Exception {
        long id = service.create(PICKY, new NewWallet("mine", null)).getId();

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
                .andExpect(jsonPath("$.error").value("no such destination wallet"));
        call(put(BASE + "/" + id + "/grants/22"), "picky", "{\"access\":\"OWNER\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isString());
        call(put(BASE + "/" + id + "/grants/22"), "picky", "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("access must be READ or WRITE"));

        assertEquals(0, service.get(PICKY, id).getBalance());
        assertEquals(1, service.list(PICKY).size(), "no stray wallet was opened");
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
