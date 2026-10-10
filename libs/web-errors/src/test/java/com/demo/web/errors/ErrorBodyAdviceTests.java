package com.demo.web.errors;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Every rejection, whoever raised it, comes back as {@code {"error": "..."}}: the one shape the frontend reads. */
class ErrorBodyAdviceTests {

    enum Role { USER, ADMIN }

    record Body(Role role) {}

    @RestController
    static class Endpoint {
        @GetMapping("/refuse")
        String refuse() {
            throw Bad.request("not like that");
        }

        @PostMapping("/role")
        String role(@RequestBody Body in) {
            return in.role().name();
        }
    }

    @RestControllerAdvice
    static class Advice extends ErrorBodyAdvice {}

    final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Endpoint()).setControllerAdvice(new Advice()).build();

    @Test
    void aDomainRejectionAndABodyMvcCannotReadShareOneShape() throws Exception {
        mvc.perform(get("/refuse")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("not like that"));
        mvc.perform(post("/role").contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ROOT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("unknown value 'ROOT', expected one of [USER, ADMIN]"));
        mvc.perform(post("/role").contentType(MediaType.APPLICATION_JSON).content("not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("request body could not be read"));
        mvc.perform(get("/refuse").accept(MediaType.TEXT_PLAIN).header("X", "y").contentType(MediaType.TEXT_PLAIN))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/refuse")).andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error").exists());
    }
}
