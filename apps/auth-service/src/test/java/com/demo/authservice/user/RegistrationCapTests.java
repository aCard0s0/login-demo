package com.demo.authservice.user;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The service-wide registration cap, over HTTP. Its own context and database on purpose: the window lives in
 * the {@code UserService} bean, so filling it here would lock every other test class in the same context out.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/test-registration-cap.db",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.hikari.maximum-pool-size=1",
})
@AutoConfigureMockMvc
class RegistrationCapTests {

    @Autowired
    MockMvc mvc;

    @Test
    void thirtyRegistrationsInAWindowIsTheCapAndRejectedOnesCountToo() throws Exception {
        for (int attempt = 0; attempt < 30; attempt++) {
            // Every third one is refused for a short password; it still spends an attempt, or a bot could probe for free.
            String password = attempt % 3 == 0 ? "tiny" : "long-enough-1";
            mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"U" + attempt + "\",\"email\":\"u" + attempt + "@example.com\",\"password\":\"" + password + "\"}"))
                    .andExpect(attempt % 3 == 0 ? status().isBadRequest() : status().isCreated());
        }
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Late\",\"email\":\"late@example.com\",\"password\":\"long-enough-1\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").isString());
    }
}
