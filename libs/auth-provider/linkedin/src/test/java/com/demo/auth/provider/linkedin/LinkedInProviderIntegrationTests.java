package com.demo.auth.provider.linkedin;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The module as a service gets it: scanned, bound from {@code oauth.linkedin.*}, and asking the OpenID userinfo. */
class LinkedInProviderIntegrationTests {

    @Configuration
    @ComponentScan("com.demo.auth.provider")
    @EnableConfigurationProperties
    static class Service {}

    @Test
    void credentialsBindFromItsOwnPrefixAndTheConsentAsksForTheOpenIdScopes() {
        new ApplicationContextRunner().withUserConfiguration(Service.class)
                .withPropertyValues("oauth.linkedin.enabled=true", "oauth.linkedin.client-id=li-id", "oauth.linkedin.client-secret=li-secret",
                        "oauth.redirect-base-url=https://app.example.com")
                .run(app -> {
                    OAuthFlow flow = app.getBean(OAuthFlow.class);
                    assertEquals(List.of("linkedin"), flow.enabled().stream().map(OAuthProvider::getKey).toList());
                    String consent = flow.consentUri(flow.enabled("linkedin").orElseThrow(), "s", "v".repeat(43));
                    assertTrue(consent.startsWith("https://www.linkedin.com/oauth/v2/authorization?"), consent);
                    assertTrue(consent.contains("scope=openid+profile+email"), "the OpenID product is what carries email_verified: " + consent);
                });
    }

    @Test
    void theIdentityComesFromUserinfoAskedAsTheTokensOwner() {
        RestClient.Builder http = RestClient.builder();
        MockRestServiceServer linkedin = MockRestServiceServer.bindTo(http).build();
        linkedin.expect(requestTo(LinkedInProvider.USERINFO_URI))
                .andExpect(header("Authorization", "Bearer li-token"))
                .andRespond(withSuccess("{\"sub\":\"abc\",\"name\":\"Ada Lovelace\",\"email\":\"ada@example.com\",\"email_verified\":true}",
                        MediaType.APPLICATION_JSON));
        linkedin.expect(requestTo(LinkedInProvider.USERINFO_URI))
                .andRespond(withSuccess("{\"sub\":\"abc\",\"email\":\"ada@example.com\",\"email_verified\":false}", MediaType.APPLICATION_JSON));
        RestClient client = http.build();

        assertEquals(new Identity("ada@example.com", "Ada Lovelace"), new LinkedInProvider().identity(client, "li-token"));
        assertThrows(IllegalStateException.class, () -> new LinkedInProvider().identity(client, "li-token"));
        linkedin.verify();
    }
}
