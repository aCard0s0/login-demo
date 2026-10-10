package com.demo.auth.provider.google;

import com.demo.auth.provider.Identity;
import com.demo.auth.provider.OAuthFlow;
import com.demo.auth.provider.OAuthProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The module as a service gets it: scanned, bound from {@code oauth.google.*}, and asking Google's userinfo. */
class GoogleProviderIntegrationTests {

    /** What a service does: scan the provider package and bind @ConfigurationProperties. */
    @Configuration
    @ComponentScan("com.demo.auth.provider")
    @EnableConfigurationProperties
    static class Service {}

    private final ApplicationContextRunner context = new ApplicationContextRunner().withUserConfiguration(Service.class);

    @Test
    void credentialsBindFromItsOwnPrefixAndOnlyAConfiguredProviderIsOffered() {
        context.withPropertyValues("oauth.google.enabled=true", "oauth.google.client-id=id", "oauth.google.client-secret=secret")
                .run(app -> {
                    assertEquals(List.of("google"), app.getBean(OAuthFlow.class).enabled().stream().map(OAuthProvider::getKey).toList());
                    assertEquals("id", app.getBean(GoogleProvider.class).getClientId());
                });
        context.withPropertyValues("oauth.google.client-id=id", "oauth.google.client-secret=secret")
                .run(app -> assertTrue(app.getBean(OAuthFlow.class).enabled().isEmpty(), "credentials alone do not switch it on"));
        context.withPropertyValues("oauth.google.enabled=true", "oauth.google.client-id=id")
                .run(app -> assertTrue(app.getBean(OAuthFlow.class).enabled().isEmpty(), "nor does a half-finished .env"));
    }

    @Test
    void theIdentityComesFromUserinfoAskedAsTheTokensOwner() {
        RestClient.Builder http = RestClient.builder();
        MockRestServiceServer google = MockRestServiceServer.bindTo(http).build();
        google.expect(requestTo(GoogleProvider.USERINFO_URI))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer at-1"))
                .andRespond(withSuccess("{\"email\":\"ada@example.com\",\"email_verified\":true,\"name\":\"Ada\"}", MediaType.APPLICATION_JSON));
        google.expect(requestTo(GoogleProvider.USERINFO_URI))
                .andRespond(withSuccess("{\"email\":\"ada@example.com\",\"email_verified\":false}", MediaType.APPLICATION_JSON));
        RestClient client = http.build();

        assertEquals(new Identity("ada@example.com", "Ada"), new GoogleProvider().identity(client, "at-1"));
        assertThrows(IllegalStateException.class, () -> new GoogleProvider().identity(client, "at-1"));
        google.verify();
    }
}
