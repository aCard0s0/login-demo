package com.demo.authservice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract the frontend and todo-service are written against: status codes, the {error} body and
 * the Authorization header. Its own database file, so re-creating the schema cannot disturb the other test class.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/test-web.db",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        // SQLite allows a single writer; one connection keeps Hibernate from tripping over itself.
        "spring.datasource.hikari.maximum-pool-size=1",
        // What compose passes in from .env. Set here too, so the seeded admin is part of the contract.
        "admin.email=admin@example.com",
        "admin.password=admin-pass-01",
        // What agent-service sends as X-Internal-Secret; compose passes the same INTERNAL_SECRET to both.
        "auth.internal-secret=test-internal-secret",
})
@AutoConfigureMockMvc
class ApiContractTests {

    @Autowired
    MockMvc mvc;

    private String register(String name, String email, String password) throws Exception {
        mvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content(json(name, email, password)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("USER"));
        return login(email, password);
    }

    private String login(String email, String password) throws Exception {
        String body = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(null, email, password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.role").isString())
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"token\"\\s*:\\s*\"([^\"]+)\".*", "$1");
    }

    /** The id behind a token, which is the only way this test can name an account it did not seed. */
    private long idOf(String token) throws Exception {
        String body = mvc.perform(get("/api/accounts/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"id\"\\s*:\\s*(\\d+).*", "$1"));
    }

    private static String role(String role) {
        return "{\"role\":\"" + role + "\"}";
    }

    private static String json(String name, String email, String password) {
        return (name == null ? "{" : "{\"name\":\"" + name + "\",")
                + "\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    @Test
    void loginHandsBackASignedJwtAndMeReadsItFromTheBearerHeader() throws Exception {
        String token = register("Ada", "ada@example.com", "correct-horse");

        assertEquals(3, token.split("\\.").length, "a JWT is header.payload.signature");

        mvc.perform(get("/api/accounts/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("ada@example.com"))
                .andExpect(jsonPath("$.name").value("Ada"));
    }

    @Test
    void aMissingOrBrokenTokenIs401InTheErrorShape() throws Exception {
        mvc.perform(get("/api/accounts/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").isString());
        mvc.perform(get("/api/accounts/me").header("Authorization", "Bearer nonsense"))
                .andExpect(status().isUnauthorized());

        String token = register("Grace", "grace@example.com", "hopper-1906");
        mvc.perform(get("/api/accounts/me").header("Authorization", "Bearer " + token.substring(0, token.length() - 2)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void editingAnAccountNeedsTheCurrentPassword() throws Exception {
        String token = register("Edna", "edna@example.com", "edna-pass-01");

        mvc.perform(put("/api/accounts/me").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Edna Mode","email":"edna.mode@example.com","currentPassword":"wrong"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("current password is wrong"));

        mvc.perform(put("/api/accounts/me").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Edna Mode","email":"edna.mode@example.com","currentPassword":"edna-pass-01"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Edna Mode"))
                .andExpect(jsonPath("$.email").value("edna.mode@example.com"));

        login("edna.mode@example.com", "edna-pass-01");
    }

    @Test
    void rejectedInputComesBackAs400AndTheErrorShape() throws Exception {
        register("Alonzo", "alonzo@example.com", "church-1903");

        mvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content(json("Alonzo again", "alonzo@example.com", "church-1903")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("that email is already registered"));

        mvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content(json("Short", "short@example.com", "tiny")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isString());

        // Rejections MVC itself makes -- a body that will not parse, an id that is not a number, a path that
        // does not exist -- must come back in the same shape, not as Spring's ProblemDetail.
        mvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isString());
        mvc.perform(put("/api/accounts/not-a-number/role").header("Authorization", "Bearer x")
                        .contentType(MediaType.APPLICATION_JSON).content(role("ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isString());
        mvc.perform(get("/api/nowhere"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").isString());
        // The admin page shows this text, so an unknown role has to say what the known ones are.
        mvc.perform(put("/api/accounts/1/role").header("Authorization", "Bearer x")
                        .contentType(MediaType.APPLICATION_JSON).content(role("KING")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("MODERATOR")));
    }

    @Test
    void badCredentialsAre401AndARunOfThemIs429() throws Exception {
        register("Alan", "alan@example.com", "enigma-1936");

        for (int attempt = 0; attempt < 5; attempt++) {
            mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
                            .content(json(null, "alan@example.com", "wrong")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("invalid credentials"));
        }

        mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(null, "alan@example.com", "enigma-1936")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").isString());
    }

    @Test
    void publicEndpointsNeedNoTokenAndTheJwksKeepsThePrivateHalfBack() throws Exception {
        register("Katherine", "katherine@example.com", "johnson-1918");

        mvc.perform(get("/api/public/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts", greaterThanOrEqualTo(1)));

        mvc.perform(get("/api/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].n").isString())
                .andExpect(jsonPath("$.keys[0].d").doesNotExist())
                .andExpect(jsonPath("$.keys[0].p").doesNotExist());
    }

    @Test
    void theAdminFromTheEnvironmentIsThereAndIsTheOnlyOneWhoCanChangeARole() throws Exception {
        String admin = login("admin@example.com", "admin-pass-01");
        String user = register("Mary", "mary@example.com", "jackson-1921");
        long maryId = idOf(user);

        mvc.perform(get("/api/accounts").header("Authorization", "Bearer " + user))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").isString());
        mvc.perform(put("/api/accounts/" + maryId + "/role").header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON).content(role("ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/accounts/" + maryId + "/role")
                        .contentType(MediaType.APPLICATION_JSON).content(role("ADMIN")))
                .andExpect(status().isUnauthorized());

        mvc.perform(put("/api/accounts/" + maryId + "/role").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content(role("MODERATOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("MODERATOR"));

        // The role is read from the account, not from the claims, so the token Mary already holds is enough.
        mvc.perform(get("/api/accounts").header("Authorization", "Bearer " + user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").isNumber())
                .andExpect(jsonPath("$[0].passwordHash").doesNotExist());

        mvc.perform(put("/api/accounts/" + idOf(admin) + "/role").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content(role("USER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isString());
    }

    @Test
    void onlyAnAdminSuspendsOrRevokesAndASuspendedAccountCannotLogIn() throws Exception {
        String admin = login("admin@example.com", "admin-pass-01");
        String user = register("Hedy", "hedy@example.com", "lamarr-1914");
        long hedyId = idOf(user);

        mvc.perform(put("/api/accounts/" + hedyId + "/suspended").header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"suspended\":true}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/accounts/" + hedyId + "/revoke").header("Authorization", "Bearer " + user))
                .andExpect(status().isForbidden());

        mvc.perform(put("/api/accounts/" + hedyId + "/suspended").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"suspended\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suspended").value(true));
        mvc.perform(get("/api/accounts/me").header("Authorization", "Bearer " + user))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(null, "hedy@example.com", "lamarr-1914")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("this account is suspended"));

        mvc.perform(put("/api/accounts/" + hedyId + "/suspended").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"suspended\":false}"))
                .andExpect(jsonPath("$.suspended").value(false));
        String again = login("hedy@example.com", "lamarr-1914");

        mvc.perform(post("/api/accounts/" + hedyId + "/revoke").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
        mvc.perform(get("/api/accounts/me").header("Authorization", "Bearer " + again))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/token-versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['" + hedyId + "']").value(2));
    }

    /** The token agent-service hands an external agent: the owner as subject, AGENT as role, the agent pinned by claim. */
    @Test
    void anAgentTokenNamesTheOwnerAndOneAgentAndDiesWithTheOwnersTokens() throws Exception {
        String ada = register("Ada", "ada-agent@example.com", "lovelace-1815");
        long adaId = idOf(ada);

        // Being on the network is not enough: without the shared secret nothing is minted.
        mvc.perform(post("/internal/agent-tokens").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":" + adaId + ",\"agentId\":42}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("internal secret missing or wrong"));
        mvc.perform(post("/internal/agent-tokens").header("X-Internal-Secret", "wrong").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":" + adaId + ",\"agentId\":42}"))
                .andExpect(status().isForbidden());

        String body = mvc.perform(post("/internal/agent-tokens").header("X-Internal-Secret", "test-internal-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":" + adaId + ",\"agentId\":42}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andReturn().getResponse().getContentAsString();
        String token = body.replaceAll(".*\"token\"\\s*:\\s*\"([^\"]+)\".*", "$1");
        com.nimbusds.jwt.JWTClaimsSet claims = com.nimbusds.jwt.SignedJWT.parse(token).getJWTClaimsSet();
        assertEquals(String.valueOf(adaId), claims.getSubject());
        assertEquals("AGENT", claims.getStringClaim("role"));
        assertEquals(42L, claims.getLongClaim("agent"));
        assertEquals(0L, claims.getLongClaim("agentVer"), "an agent never revoked is at version zero");
        long days = java.time.Duration.between(java.time.Instant.now(), claims.getExpirationTime().toInstant()).toDays();
        assertEquals(29, days, "30 days, minus the seconds this test took");

        // Revoking one agent bumps its version alone: the feed says so under agent:<id>, the next token carries it,
        // and the owner's own account is not in the feed at all.
        mvc.perform(post("/internal/agent-tokens/42/revoke")).andExpect(status().isForbidden());
        mvc.perform(post("/internal/agent-tokens/42/revoke").header("X-Internal-Secret", "test-internal-secret"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(get("/internal/token-versions")).andExpect(status().isOk())
                .andExpect(jsonPath("$['agent:42']").value(1))
                .andExpect(jsonPath("$['" + adaId + "']").doesNotExist());
        String fresh = mvc.perform(post("/internal/agent-tokens").header("X-Internal-Secret", "test-internal-secret")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"accountId\":" + adaId + ",\"agentId\":42}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(1L, com.nimbusds.jwt.SignedJWT.parse(fresh.replaceAll(".*\"token\"\\s*:\\s*\"([^\"]+)\".*", "$1"))
                .getJWTClaimsSet().getLongClaim("agentVer"));

        // Its only way in is /mcp: here it could edit its owner's account, so every /api endpoint answers it 403.
        mvc.perform(get("/api/accounts/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("an agent token can only connect to /mcp"));
        mvc.perform(put("/api/accounts/me").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Taken","email":"taken@example.com","currentPassword":"lovelace-1815","newPassword":"taken-over-1"}"""))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/accounts").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        mvc.perform(get("/api/accounts/me").header("Authorization", "Bearer " + ada))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value("ada-agent@example.com"));
        login("ada-agent@example.com", "lovelace-1815");
        // And a revoke kills it like any other: dead first, so a revoked agent token is 401, not 403.
        String admin = login("admin@example.com", "admin-pass-01");
        mvc.perform(post("/api/accounts/" + adaId + "/revoke").header("Authorization", "Bearer " + admin)).andExpect(status().isOk());
        mvc.perform(get("/api/accounts/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());

        mvc.perform(post("/internal/agent-tokens").header("X-Internal-Secret", "test-internal-secret").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":999999,\"agentId\":1}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("account not found"));
        mvc.perform(post("/internal/agent-tokens").header("X-Internal-Secret", "test-internal-secret").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":" + adaId + "}"))
                .andExpect(status().isBadRequest());
    }
}
