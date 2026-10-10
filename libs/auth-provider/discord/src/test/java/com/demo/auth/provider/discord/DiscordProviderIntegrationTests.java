package com.demo.auth.provider.discord;

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

/** The module as a service gets it: scanned, bound from {@code oauth.discord.*}, and asking /users/@me. */
class DiscordProviderIntegrationTests {

    @Configuration
    @ComponentScan("com.demo.auth.provider")
    @EnableConfigurationProperties
    static class Service {}

    @Test
    void credentialsBindFromItsOwnPrefixAndTheConsentAsksForTheEmailScope() {
        new ApplicationContextRunner().withUserConfiguration(Service.class)
                .withPropertyValues("oauth.discord.enabled=true", "oauth.discord.client-id=dc-id", "oauth.discord.client-secret=dc-secret",
                        "oauth.redirect-base-url=https://app.example.com")
                .run(app -> {
                    OAuthFlow flow = app.getBean(OAuthFlow.class);
                    assertEquals(List.of("discord"), flow.enabled().stream().map(OAuthProvider::getKey).toList());
                    String consent = flow.consentUri(flow.enabled("discord").orElseThrow(), "s", "v".repeat(43));
                    assertTrue(consent.startsWith("https://discord.com/oauth2/authorize?"), consent);
                    assertTrue(consent.contains("scope=identify+email"), "identify alone carries no address: " + consent);
                });
    }

    @Test
    void theIdentityComesFromUsersMeAskedAsTheTokensOwner() {
        RestClient.Builder http = RestClient.builder();
        MockRestServiceServer discord = MockRestServiceServer.bindTo(http).build();
        discord.expect(requestTo(DiscordProvider.ME_URI))
                .andExpect(header("Authorization", "Bearer dc-token"))
                .andRespond(withSuccess("{\"id\":\"1\",\"username\":\"ada_l\",\"global_name\":null,\"email\":\"ada@example.com\",\"verified\":true}",
                        MediaType.APPLICATION_JSON));

        assertEquals(new Identity("ada@example.com", "ada_l"), new DiscordProvider().identity(http.build(), "dc-token"),
                "a null global_name, as Discord really answers, still makes an identity");
        discord.verify();
    }
}
