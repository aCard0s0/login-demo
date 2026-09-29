package com.demo.authservice.oauth;

import com.demo.authservice.account.Account;
import com.demo.authservice.account.AccountService;
import com.demo.authservice.session.SessionService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The half of the provider flow that does not need a provider: which buttons a deployment offers, where the
 * browser is sent, and what happens to a callback that did not start here. The code-for-token exchange is
 * the other half and is not covered -- faking it would test the fake.
 *
 * <p>Google is switched on with throwaway credentials and GitHub is left off, so one run covers both sides
 * of the enable switch. Its own database file, so re-creating the schema cannot disturb another test class.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/test-oauth.db",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        // SQLite allows a single writer; one connection keeps Hibernate from tripping over itself.
        "spring.datasource.hikari.maximum-pool-size=1",
        "oauth.redirect-base-url=http://localhost:3000",
        "oauth.google.enabled=true",
        "oauth.google.client-id=test-client",
        "oauth.google.client-secret=test-secret",
        // Enabled but with no credentials, which must count as off rather than as a broken button.
        "oauth.github.enabled=true",
})
@AutoConfigureMockMvc
class OAuthTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    AccountService accounts;

    @Autowired
    SessionService sessions;

    @Test
    void onlyFullyConfiguredProvidersAreOfferedAndTheRestAre404() throws Exception {
        mvc.perform(get("/api/oauth/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].key").value("google"))
                .andExpect(jsonPath("$[0].label").value("Google"));

        mvc.perform(get("/api/oauth/github/start")).andExpect(status().isNotFound());
        mvc.perform(get("/api/oauth/myspace/start")).andExpect(status().isNotFound());
    }

    @Test
    void startSendsTheBrowserToConsentAndPlantsTheStateItWillCheck() throws Exception {
        var response = mvc.perform(get("/api/oauth/google/start"))
                .andExpect(status().isFound())
                .andExpect(header().string("Set-Cookie", containsString("HttpOnly")))
                .andReturn().getResponse();

        String consent = response.getHeader("Location");
        assertNotNull(consent);
        assertTrue(consent.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"), consent);
        assertTrue(consent.contains("client_id=test-client"), consent);
        assertTrue(consent.contains("redirect_uri=http%3A%2F%2Flocalhost%3A3000%2Fapi%2Foauth%2Fgoogle%2Fcallback"),
                "the provider must be told the address the browser uses, not this service's: " + consent);

        String state = response.getCookie("oauth_state").getValue();
        assertTrue(consent.contains("state=" + state), "the planted state must be the one echoed to the provider");
        assertTrue(consent.contains("response_type=code"), "the secret must stay in the back channel: " + consent);
    }

    @Test
    void aCallbackThatDidNotStartHereNeverReachesTheProvider() throws Exception {
        // No cookie at all, which is what a forged callback from another site looks like.
        mvc.perform(get("/api/oauth/google/callback").param("code", "stolen").param("state", "guessed"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", startsWith("http://localhost:3000/login#error=")));

        // A cookie that disagrees with the query is the same refusal.
        mvc.perform(get("/api/oauth/google/callback").param("code", "stolen").param("state", "guessed")
                        .cookie(new Cookie("oauth_state", "something-else")))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", startsWith("http://localhost:3000/login#error=")));
    }

    @Test
    void aProviderIdentityFindsTheAccountWithThatEmailOrMakesOne() throws Exception {
        Account existing = accounts.register("Ada", "ada@example.com", "correct-horse");

        assertEquals(existing.getId(), accounts.findOrCreateFromOAuth("ADA@example.com", "Ada L").getId(),
                "a verified provider email must land on the account that already owns it, not a second one");
        assertEquals("Ada", accounts.findOrCreateFromOAuth("ada@example.com", "Ada L").getName(),
                "and must not overwrite what the account owner set here");

        Account made = accounts.findOrCreateFromOAuth("grace@example.com", null);
        assertEquals("grace", made.getName(), "a provider with no name to give falls back to the address");
        assertEquals(made.getId(), accounts.findOrCreateFromOAuth("grace@example.com", null).getId(),
                "a second sign-in is the same account");

        assertTrue(sessions.login("grace@example.com", "").isEmpty(),
                "an account made this way must have no password anyone can guess");
    }
}
