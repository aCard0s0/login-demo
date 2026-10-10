package com.demo.agentservice.mcp;

import com.demo.agentservice.activity.Activity;
import com.demo.agentservice.activity.ActivityLog;
import com.demo.agentservice.agent.entities.Agent;
import com.demo.agentservice.agent.entities.AgentMcpServer;
import com.demo.agentservice.agent.ServerUrls;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * The MCP servers an agent's owner attached, seen through that agent's permissions.
 *
 * <p>Permission is decided in two places on purpose. {@link #list} offers only the tools the access on each
 * server row allows, so a READ server's writing tools are not even described to the connecting agent.
 * {@link #call} then checks the row as it is in the database right then, so an owner who flips a server to
 * READ between two calls is obeyed from the next one -- and an agent that calls a tool it was never offered
 * is refused all the same.
 *
 * <p>Which tools "only read" is the server's own {@code readOnlyHint} for a server the deployment trusts, and
 * the owner's {@code readOnlyTools} list for any other: a server an owner typed in can annotate anything.
 */
// ponytail: stateless, so every call still connects to the one downstream server afresh: initialize, call, close.
// The listing it needs for the read-only check comes from a short cache; keeping the connection is the upgrade.
final class McpTools {

    private static final Logger log = LoggerFactory.getLogger(McpTools.class);

    /** The usual ceiling on tool names in model APIs; kept so a long server + tool pair still fits. */
    private static final int MAX_NAME = 64;

    /** How long a server's listing is reused. A tool the server adds or drops shows up here within this. */
    static final Duration LISTING_TTL = Duration.ofSeconds(15);

    private static final ToolListCache LISTINGS = new ToolListCache(LISTING_TTL, Instant::now);

    private McpTools() {}

    /**
     * Every tool the agent may use right now, named {@code server__tool}. A server that cannot be reached is
     * written to the log and skipped, so one dead URL does not hide the others.
     */
    static List<Tool> list(Agent agent, String bearer, ActivityLog activity, ServerUrls urls) {
        Map<String, Tool> offered = new LinkedHashMap<>();
        Set<String> clashed = new HashSet<>();
        for (AgentMcpServer server : agent.getServers()) {
            try {
                for (Tool tool : tools(server, bearer, urls)) {
                    if (server.getAccess().allows(readOnly(server, tool, urls))) {
                        Tool mine = renamed(server, tool);
                        // Long names cut to the same 64 characters: offer none of them as that name, rather than one at random.
                        if (offered.putIfAbsent(mine.name(), mine) != null) {
                            log.warn("agent {} server '{}': tool '{}' truncates to '{}' like another; not offered", agent.getId(), server.getName(), tool.name(), mine.name());
                            clashed.add(mine.name());
                        }
                    }
                }
            } catch (Exception e) {
                // Clients list tools often; one line per failure, not one per listing, or the log is nothing else.
                activity.recordOnce(agent.getId(), Activity.TOOL_CALL,
                        "server '" + server.getName() + "': could not connect: " + ActivityLog.brief(e.getMessage()));
            }
        }
        offered.keySet().removeAll(clashed);
        return new ArrayList<>(offered.values());
    }

    /** Runs one tool on the server its name points at, after checking that server's row as it is right now. */
    static ToolResult call(Agent agent, String bearer, String name, Map<String, Object> args, ServerUrls urls) {
        AgentMcpServer server = serverOf(agent, name);
        List<Tool> matching = tools(server, bearer, urls).stream()
                .filter(t -> offeredName(server.getName(), t.name()).equals(name)).toList();
        if (matching.size() > 1) {
            throw new AccessDenied("ambiguous tool: " + matching.size() + " tools on server '" + server.getName() + "' truncate to " + name);
        }
        Tool tool = matching.stream().findFirst().orElseThrow(() -> new AccessDenied("no such tool: " + name));
        if (!server.getAccess().allows(readOnly(server, tool, urls))) {
            throw new AccessDenied("needs WRITE on server '" + server.getName() + "' (has " + server.getAccess() + ")");
        }
        try (McpSyncClient client = connect(server, bearer, urls)) {
            // Passed through whole: an image, an embedded resource or structured content reaches the model as it left the server.
            return ToolResult.of(client.callTool(new CallToolRequest(tool.name(), args)));
        }
    }

    /**
     * The server's own listing, from the cache when it is fresh. The URL policy runs first either way, so a cached
     * listing never lets a server that has since moved somewhere private through; the permission check is the caller's.
     */
    private static List<Tool> tools(AgentMcpServer server, String bearer, ServerUrls urls) {
        urls.checkBeforeConnect(server.getUrl());
        return LISTINGS.get(server.getUrl(), credential(server, bearer, urls), () -> {
            try (McpSyncClient client = connect(server, bearer, urls)) {
                return client.listTools().tools();
            }
        });
    }

    /**
     * What the server is sent as {@code Authorization}. The caller's token goes only to the deployment's own servers:
     * {@code AgentService} refuses to save the forward on any other URL, and this is the same rule at the moment of
     * use, so a trusted list edited since the row was saved cannot leak the token either.
     */
    private static String credential(AgentMcpServer server, String bearer, ServerUrls urls) {
        if (server.isForwardCallerToken()) {
            return urls.trusted(server.getUrl()) ? "Bearer " + bearer : null;
        }
        return server.getAuthHeader();
    }

    /** The server whose name prefixes the tool name; the longest match when one server's name begins another's. */
    private static AgentMcpServer serverOf(Agent agent, String name) {
        AgentMcpServer best = null;
        for (AgentMcpServer server : agent.getServers()) {
            String prefix = offeredName(server.getName(), "");
            if (name.startsWith(prefix) && (best == null || prefix.length() > offeredName(best.getName(), "").length())) {
                best = server;
            }
        }
        if (best == null) {
            throw new AccessDenied("no such tool: " + name);
        }
        return best;
    }

    private static McpSyncClient connect(AgentMcpServer server, String bearer, ServerUrls urls) {
        // Against what the name resolves to right now, not only what it resolved to when the row was saved.
        urls.checkBeforeConnect(server.getUrl());
        String auth = credential(server, bearer, urls);
        URI url = URI.create(server.getUrl());
        String base = url.getScheme() + "://" + url.getRawAuthority();
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(base)
                .endpoint(endpoint(url))
                .clientBuilder(new SharedHttpClient())
                .httpRequestCustomizer((request, method, uri, body, context) -> {
                    if (auth != null) {
                        request.header("Authorization", auth);
                    }
                })
                .build();
        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(30))
                .initializationTimeout(Duration.ofSeconds(10))
                .build();
        try {
            client.initialize();
        } catch (RuntimeException e) {
            client.close();
            throw e;
        }
        return client;
    }

    /**
     * One JDK client for every downstream connection. The SDK builds a client per transport and never closes it, so
     * each would keep a selector thread and connection pool alive until the collector found it; this builder hands
     * the SDK the same client every time and ignores what it tries to set on it, which is only the connect timeout.
     */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            // A public server answering 302 to a private address would be the URL check, undone.
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private static final class SharedHttpClient implements HttpClient.Builder {
        public HttpClient build() { return HTTP; }
        public HttpClient.Builder cookieHandler(CookieHandler h) { return this; }
        public HttpClient.Builder connectTimeout(Duration d) { return this; }
        public HttpClient.Builder sslContext(SSLContext c) { return this; }
        public HttpClient.Builder sslParameters(SSLParameters p) { return this; }
        public HttpClient.Builder executor(Executor e) { return this; }
        public HttpClient.Builder followRedirects(HttpClient.Redirect r) { return this; }
        public HttpClient.Builder version(HttpClient.Version v) { return this; }
        public HttpClient.Builder priority(int p) { return this; }
        public HttpClient.Builder proxy(ProxySelector s) { return this; }
        public HttpClient.Builder authenticator(Authenticator a) { return this; }
    }

    /** Path and query of the stored URL, as the SDK's endpoint: {@code /mcp?agent=5} must keep its {@code ?agent=5}. */
    static String endpoint(URI url) {
        String path = url.getRawPath() == null || url.getRawPath().isEmpty() ? "/" : url.getRawPath();
        return url.getRawQuery() == null ? path : path + "?" + url.getRawQuery();
    }

    /**
     * The one rule READ keys on. A trusted server's own annotation counts, and a tool that does not say it is
     * read-only is taken to write; for any other server only the owner's list counts, and the annotation is ignored.
     */
    static boolean readOnly(AgentMcpServer server, Tool tool, ServerUrls urls) {
        if (urls.trusted(server.getUrl())) {
            return tool.annotations() != null && Boolean.TRUE.equals(tool.annotations().readOnlyHint());
        }
        return server.readOnlyToolSet().contains(tool.name());
    }

    /** {@code server__tool}, in the characters every client allows. Built-in tool names never contain "__". */
    static String offeredName(String server, String tool) {
        String name = (server + "__" + tool).replaceAll("[^A-Za-z0-9_-]", "_");
        return name.length() > MAX_NAME ? name.substring(0, MAX_NAME) : name;
    }

    /** The tool as the server described it, under the name the connecting agent will call it by. */
    private static Tool renamed(AgentMcpServer server, Tool tool) {
        Tool.Builder copy = Tool.builder()
                .name(offeredName(server.getName(), tool.name()))
                .description(tool.description() == null ? tool.name() : tool.description())
                .inputSchema(tool.inputSchema() == null ? Map.of("type", "object") : tool.inputSchema());
        if (tool.annotations() != null) {
            copy.annotations(tool.annotations());
        }
        return copy.build();
    }
}
