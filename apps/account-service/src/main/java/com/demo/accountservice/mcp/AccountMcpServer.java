package com.demo.accountservice.mcp;

import com.demo.accountservice.account.entities.Account;
import com.demo.accountservice.account.AccountService;
import com.demo.accountservice.account.entities.Transfer;
import com.demo.accountservice.token.Caller;
import com.demo.accountservice.token.JwtVerifier;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * The same accounts, reachable by an agent over MCP at {@code /mcp}. Three tools, each the thin MCP face of
 * one {@link AccountService} method, so the ownership and permission rules there apply to an agent exactly
 * as they do to the browser. Opening accounts, depositing and granting are not offered: an agent never may.
 *
 * <p>Who is asking comes from the {@code Authorization} header on the MCP request -- the token the agent
 * connected to agent-service with, forwarded as-is. Only an agent token is served: it names its agent, so the
 * agent gets the accounts opened for it and the ones it was granted. An owner's login token is refused,
 * because through an agent it would hand that agent the owner's whole reach.
 * Only the two reading tools declare themselves read-only; that annotation is what agent-service's READ
 * permission keys on, so leaving it off {@code transfer} is the one thing this class must never do.
 *
 * <p>Stateless, and outside {@code /api}, so the web proxy never forwards it; only agent-service reaches it.
 */
@Configuration
public class AccountMcpServer {

    static final String AUTHORIZATION = "authorization";

    @Bean
    ServletRegistrationBean<HttpServletStatelessServerTransport> mcpServlet(AccountService accounts, JwtVerifier jwt) {
        HttpServletStatelessServerTransport transport = HttpServletStatelessServerTransport.builder()
                .messageEndpoint("/mcp")
                .contextExtractor(request -> McpTransportContext.create(
                        Map.of(AUTHORIZATION, Objects.requireNonNullElse(request.getHeader("Authorization"), ""))))
                .build();
        McpServer.sync(transport)
                .serverInfo("account-service", "0.0.1")
                .capabilities(ServerCapabilities.builder().tools(false).build())
                .tools(tools(accounts, jwt))
                .build();
        ServletRegistrationBean<HttpServletStatelessServerTransport> servlet = new ServletRegistrationBean<>(transport, "/mcp");
        servlet.setName("mcp");
        servlet.setAsyncSupported(true);
        return servlet;
    }

    private static List<SyncToolSpecification> tools(AccountService accounts, JwtVerifier jwt) {
        return List.of(
                tool(Tool.builder()
                                .name("list_accounts")
                                .description("List the accounts you may use, one per line as '#id name: balance cents'.")
                                .inputSchema(schema(Map.of(), List.of()))
                                .annotations(ToolAnnotations.builder().readOnlyHint(true).build())
                                .build(),
                        jwt, (caller, args) -> {
                            List<Account> mine = accounts.list(caller);
                            return mine.isEmpty() ? "no accounts"
                                    : mine.stream().map(AccountMcpServer::line).collect(Collectors.joining("\n"));
                        }),
                tool(Tool.builder()
                                .name("list_transfers")
                                .description("List an account's latest 100 transfers, newest first, as '#id from -> to amount (by)'. A deposit has from '-'.")
                                .inputSchema(schema(Map.of("account", Map.of("type", "integer")), List.of("account")))
                                .annotations(ToolAnnotations.builder().readOnlyHint(true).build())
                                .build(),
                        jwt, (caller, args) -> {
                            List<Transfer> all = accounts.transfers(caller, number(args, "account"));
                            return all.isEmpty() ? "no transfers"
                                    : all.stream().map(AccountMcpServer::line).collect(Collectors.joining("\n"));
                        }),
                tool(Tool.builder()
                                .name("transfer")
                                .description("Move an amount of cents from one of your accounts to another account.")
                                .inputSchema(schema(Map.of(
                                        "from", Map.of("type", "integer"),
                                        "to", Map.of("type", "integer"),
                                        "amount", Map.of("type", "integer")), List.of("from", "to", "amount")))
                                .build(),
                        jwt, (caller, args) -> line(accounts.transfer(caller,
                                number(args, "from"), number(args, "to"), number(args, "amount")))));
    }

    /** One tool: resolve the caller from the request's token, run the action, and render any refusal as an error result. */
    private static SyncToolSpecification tool(Tool tool, JwtVerifier jwt,
                                              BiFunction<Caller, Map<String, Object>, String> action) {
        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((context, request) -> {
                    try {
                        Caller caller = jwt.callerOf((String) context.get(AUTHORIZATION));
                        // agent-service forwards whatever token the client connected with. The owner's login token
                        // would give an agent the owner's whole reach here, so only an agent token is served.
                        if (!caller.isAgent()) {
                            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "connect with the agent's own token, not a login token");
                        }
                        Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();
                        return CallToolResult.builder().addTextContent(action.apply(caller, args)).build();
                    } catch (ResponseStatusException e) {
                        String reason = e.getReason() == null ? e.getStatusCode().toString() : e.getReason();
                        return CallToolResult.builder().isError(true).addTextContent(reason).build();
                    }
                })
                .build();
    }

    private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        return Map.of("type", "object", "properties", properties, "required", required);
    }

    private static String line(Account a) {
        return "#" + a.getId() + " " + a.getName() + ": " + a.getBalance() + " cents"
                + (a.getAgentId() == null ? "" : " (agent " + a.getAgentId() + ")");
    }

    private static String line(Transfer t) {
        return "#" + t.getId() + " " + (t.getFromAccount() == null ? "-" : "#" + t.getFromAccount())
                + " -> #" + t.getToAccount() + " " + t.getAmount() + " (" + t.getBy() + ")";
    }

    /**
     * A whole number that fits a long. 40.9 cents is refused rather than silently becoming 40, 5.7 is not account 5,
     * and 2^64+5 is not account 5 either: exact, or an error.
     */
    private static Long number(Map<String, Object> args, String key) {
        try {
            return new BigDecimal(String.valueOf(args.get(key))).longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " must be a whole number");
        }
    }
}
