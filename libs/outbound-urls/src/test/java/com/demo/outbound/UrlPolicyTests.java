package com.demo.outbound;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule that keeps a user's URL from pointing a service at its own network. Names resolve through a table
 * here, not DNS, so the test runs offline and can move a name between two answers.
 */
class UrlPolicyTests {

    static final String PUBLIC = "93.184.216.34";

    /** Name -> address; a literal resolves to itself, anything unknown does not resolve. */
    final Map<String, String> dns = new HashMap<>(Map.of(
            "public.example", PUBLIC,
            "metadata.example", "169.254.169.254",
            "intranet.example", "10.1.2.3",
            "two-faced.example", PUBLIC));

    final Set<String> trusted = Set.of("http://todo-service:9082/mcp", "http://wallet-service:9084/mcp", "http://127.0.0.1:9999/fake");

    final UrlPolicy urls = new UrlPolicy(() -> trusted, host -> {
        String answer = dns.get(host);
        if (answer == null && (host.contains(":") || host.matches("[0-9.]+"))) {
            answer = host;
        }
        if (answer == null) {
            throw new UnknownHostException(host);
        }
        return new InetAddress[] {InetAddress.getByName(answer)};
    });

    private String refusedAtSave(String url) {
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> urls.clean(url), url);
        assertEquals(400, e.getStatusCode().value());
        return e.getReason();
    }

    @Test
    void everyPrivateCategoryIsRefusedAtSaveAndAPublicUrlIsNot() {
        assertEquals("https://public.example/mcp", urls.clean("  https://public.example/mcp "));
        assertEquals("http://public.example:8080/a?b=c", urls.clean("http://public.example:8080/a?b=c"));

        for (String url : List.of(
                "http://127.0.0.1/mcp", "http://[::1]/mcp", "http://0.0.0.0/", "http://2130706433/",     // loopback, any-local
                "http://10.0.0.1/", "http://172.16.0.1/", "http://192.168.1.1/", "http://[fd00::1]/",    // private, unique-local
                "http://169.254.169.254/latest/meta-data", "http://[fe80::1]/",                          // link-local, cloud metadata
                "http://100.64.0.1/", "http://224.0.0.1/", "http://[::ffff:127.0.0.1]/",                 // CGNAT, multicast, mapped loopback
                "http://metadata.example/latest/meta-data", "http://intranet.example/")) {               // by name
            assertTrue(refusedAtSave(url).contains("resolves to"), url);
        }
        for (String url : List.of("http://db:5432/", "http://auth-service:9081/internal/token-versions", "http://localhost:9081/")) {
            assertTrue(refusedAtSave(url).contains("bare name"), url);
        }
        assertTrue(refusedAtSave("http://nowhere.example/").contains("does not resolve"));
        for (String url : Arrays.asList("ftp://public.example/", "public.example/mcp", "", "http://", "not a url", null)) {
            assertTrue(refusedAtSave(url).startsWith("url must be"), url);
        }
    }

    @Test
    void theDeploymentsOwnServersAreTrustedExactlyAsWritten() {
        assertEquals("http://todo-service:9082/mcp", urls.clean("http://todo-service:9082/mcp"));
        assertEquals("http://wallet-service:9084/mcp", urls.clean("http://wallet-service:9084/mcp"));
        assertEquals("http://127.0.0.1:9999/fake", urls.clean("http://127.0.0.1:9999/fake"));
        urls.checkBeforeConnect("http://todo-service:9082/mcp");
        // Exactly: a different port, path or scheme on a trusted host is not the trusted server.
        refusedAtSave("http://wallet-service:9085/mcp");
        refusedAtSave("http://wallet-service:9084/other");
        refusedAtSave("https://todo-service:9082/mcp");
    }

    @Test
    void aNameThatMovesToAPrivateAddressAfterSavingIsRefusedAtConnect() {
        String saved = urls.clean("https://two-faced.example/mcp");
        urls.checkBeforeConnect(saved);

        dns.put("two-faced.example", "10.0.0.7");   // the rebinding
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> urls.checkBeforeConnect(saved));
        assertTrue(e.getMessage().startsWith("refused: 'two-faced.example' resolves to 10.0.0.7"), e.getMessage());

        dns.remove("two-faced.example");
        assertTrue(assertThrows(IllegalStateException.class, () -> urls.checkBeforeConnect(saved)).getMessage().contains("does not resolve"));
    }

    /** The trusted set is read on every check, so a deployment that learns a server's address after startup is obeyed. */
    @Test
    void theTrustedListIsReadOnEveryCheck() {
        Set<String>[] now = new Set[] {Set.of()};
        UrlPolicy late = new UrlPolicy(() -> now[0], host -> {
            throw new UnknownHostException(host);
        });
        assertThrows(ResponseStatusException.class, () -> late.clean("http://todo-service:9082/mcp"));
        now[0] = Set.of("http://todo-service:9082/mcp");
        assertEquals("http://todo-service:9082/mcp", late.clean("http://todo-service:9082/mcp"));
    }
}
