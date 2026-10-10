package com.demo.auth.provider.github;

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

/** The module as a service gets it: scanned, bound from {@code oauth.github.*}, and asking GitHub's API. */
class GitHubProviderIntegrationTests {

    /** What a service does: scan the provider package and bind @ConfigurationProperties. */
    @Configuration
    @ComponentScan("com.demo.auth.provider")
    @EnableConfigurationProperties
    static class Service {}

    @Test
    void credentialsBindFromItsOwnPrefixAndTheConsentAsksForTheEmailScope() {
        new ApplicationContextRunner().withUserConfiguration(Service.class)
                .withPropertyValues("oauth.github.enabled=true", "oauth.github.client-id=gh-id", "oauth.github.client-secret=gh-secret",
                        "oauth.redirect-base-url=https://app.example.com")
                .run(app -> {
                    OAuthFlow flow = app.getBean(OAuthFlow.class);
                    assertEquals(List.of("github"), flow.enabled().stream().map(OAuthProvider::getKey).toList());
                    String consent = flow.consentUri(flow.enabled("github").orElseThrow(), "s", "v".repeat(43));
                    assertTrue(consent.startsWith("https://github.com/login/oauth/authorize?"), consent);
                    assertTrue(consent.contains("scope=read%3Auser+user%3Aemail"),
                            "without user:email a private address cannot be read at all: " + consent);
                    assertTrue(consent.contains("redirect_uri=https%3A%2F%2Fapp.example.com%2Fapi%2Foauth%2Fgithub%2Fcallback"), consent);
                });
    }

    @Test
    void theIdentityComesFromTheProfileAndTheVerifiedPrimaryAddress() {
        RestClient.Builder http = RestClient.builder();
        MockRestServiceServer github = MockRestServiceServer.bindTo(http).build();
        github.expect(requestTo(GitHubProvider.USER_URI))
                .andExpect(header("Authorization", "Bearer gh-token"))
                .andRespond(withSuccess("{\"login\":\"ada\",\"name\":null,\"email\":null}", MediaType.APPLICATION_JSON));
        github.expect(requestTo(GitHubProvider.EMAILS_URI))
                .andExpect(header("Authorization", "Bearer gh-token"))
                .andRespond(withSuccess("""
                        [{"email":"old@example.com","primary":false,"verified":true},
                         {"email":"ada@example.com","primary":true,"verified":true}]""", MediaType.APPLICATION_JSON));

        assertEquals(new Identity("ada@example.com", "ada"), new GitHubProvider().identity(http.build(), "gh-token"),
                "a private address and a null name, as GitHub really answers, still make an identity");
        github.verify();
    }
}
