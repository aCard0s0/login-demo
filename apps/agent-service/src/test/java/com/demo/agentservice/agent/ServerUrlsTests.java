package com.demo.agentservice.agent;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The policy itself is tested in libs/outbound-urls; here, only how the two properties feed it. */
class ServerUrlsTests {

    @Test
    void bothPropertiesFeedTheTrustedSetStrippedAndWithoutBlanks() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("agents.todo-mcp-url", " http://todo-service:9082/mcp ")
                .withProperty("agents.trusted-server-urls", " http://wallet-service:9084/mcp ,, http://127.0.0.1:9999/fake ,");
        assertEquals(Set.of("http://todo-service:9082/mcp", "http://wallet-service:9084/mcp", "http://127.0.0.1:9999/fake"), ServerUrls.configured(env));
        assertEquals(Set.of(), ServerUrls.configured(new MockEnvironment()), "nothing configured, nothing trusted");

        ServerUrls urls = new ServerUrls(env, host -> {
            throw new java.net.UnknownHostException(host);
        });
        assertEquals("http://todo-service:9082/mcp", urls.clean("http://todo-service:9082/mcp"), "the built-in server passes by name");
        assertThrows(ResponseStatusException.class, () -> urls.clean("http://todo-service:9083/mcp"), "exactly as written");

        // Read per check: a property that appears after construction is honoured.
        env.setProperty("agents.trusted-server-urls", "http://late:1/mcp");
        assertEquals("http://late:1/mcp", urls.clean("http://late:1/mcp"));
        assertThrows(ResponseStatusException.class, () -> urls.clean("http://wallet-service:9084/mcp"), "and one that went away is not");
    }
}
