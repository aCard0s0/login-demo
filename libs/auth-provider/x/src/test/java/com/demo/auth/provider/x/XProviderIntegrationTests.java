package com.demo.auth.provider.x;

import com.demo.auth.provider.Identity;
import com.demo.auth.provider.OAuthFlow;
import com.demo.auth.provider.OAuthProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The module as a service gets it: scanned, bound from {@code oauth.x.*}, Basic on the token request, and asking /2/users/me. */
class XProviderIntegrationTests {

    @Configuration
    @ComponentScan("com.demo.auth.provider")
    @EnableConfigurationProperties
    static class Service {}

    @Test
    void credentialsBindFromItsOwnPrefixAndTheConsentAsksForTheEmailScope() {
        new ApplicationContextRunner().withUserConfiguration(Service.class)
                .withPropertyValues("oauth.x.enabled=true", "oauth.x.client-id=x-id", "oauth.x.client-secret=x-secret",
                        "oauth.redirect-base-url=https://app.example.com")
                .run(app -> {
                    OAuthFlow flow = app.getBean(OAuthFlow.class);
                    OAuthProvider x = flow.enabled("x").orElseThrow();
                    assertEquals(List.of("x"), flow.enabled().stream().map(OAuthProvider::getKey).toList());
                    assertTrue(x.basicClientAuth(), "X reads the secret from the Authorization header only");
                    String consent = flow.consentUri(x, "s", "v".repeat(43));
                    assertTrue(consent.startsWith("https://x.com/i/oauth2/authorize?"), consent);
                    assertTrue(consent.contains("scope=tweet.read+users.read+users.email"),
                            "without users.email confirmed_email is never returned: " + consent);
                    assertTrue(consent.contains("code_challenge_method=S256"), "X requires PKCE: " + consent);
                });
    }

    @Test
    void theIdentityComesFromUsersMeWithTheConfirmedEmailFieldAsked() {
        RestClient.Builder http = RestClient.builder();
        MockRestServiceServer x = MockRestServiceServer.bindTo(http).build();
        x.expect(requestTo(XProvider.ME_URI))
                .andExpect(header("Authorization", "Bearer x-token"))
                .andRespond(withSuccess("{\"data\":{\"id\":\"1\",\"name\":\"Ada L\",\"username\":\"ada\",\"confirmed_email\":\"ada@example.com\"}}",
                        MediaType.APPLICATION_JSON));

        assertEquals(new Identity("ada@example.com", "Ada L"), new XProvider().identity(http.build(), "x-token"));
        assertTrue(XProvider.ME_URI.contains("user.fields=confirmed_email"), "X returns only the fields asked for");
        x.verify();
    }
}
