package com.demo.authservice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

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
                .andExpect(jsonPath("$.email").value(email));
        return login(email, password);
    }

    private String login(String email, String password) throws Exception {
        String body = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(null, email, password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"token\"\\s*:\\s*\"([^\"]+)\".*", "$1");
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
}
