package com.demo.accountservice.mcp;

import com.demo.accountservice.account.AccountService;
import com.demo.accountservice.account.entities.Account;
import com.demo.accountservice.account.entities.Transfer;
import com.demo.auth.client.Caller;
import com.demo.auth.client.JwtVerifier;
import com.demo.mcp.server.McpEndpoint;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.demo.mcp.server.Args.number;
import static com.demo.mcp.server.McpEndpoint.schema;
import static com.demo.mcp.server.McpEndpoint.tool;

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
 * <p>The endpoint itself -- stateless, outside {@code /api} -- is {@link McpEndpoint}'s.
 */
@Configuration
public class AccountMcpServer {

    @Bean
    ServletRegistrationBean<HttpServletStatelessServerTransport> mcpServlet(AccountService accounts, JwtVerifier jwt) {
        return McpEndpoint.servlet("account-service", tools(accounts, authorization -> agentOnly(jwt.callerOf(authorization))));
    }

    /**
     * agent-service forwards whatever token the client connected with. The owner's login token would give an
     * agent the owner's whole reach here, so only an agent token is served.
     */
    private static Caller agentOnly(Caller caller) {
        if (!caller.isAgent()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "connect with the agent's own token, not a login token");
        }
        return caller;
    }

    private static List<SyncToolSpecification> tools(AccountService accounts, Function<String, Caller> callerOf) {
        return List.of(
                tool(Tool.builder()
                                .name("list_accounts")
                                .description("List the accounts you may use, one per line as '#id name: balance cents'.")
                                .inputSchema(schema(Map.of(), List.of()))
                                .annotations(ToolAnnotations.builder().readOnlyHint(true).build())
                                .build(),
                        callerOf, (caller, args) -> {
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
                        callerOf, (caller, args) -> {
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
                        callerOf, (caller, args) -> line(accounts.transfer(caller,
                                number(args, "from"), number(args, "to"), number(args, "amount")))));
    }

    private static String line(Account a) {
        return "#" + a.getId() + " " + a.getName() + ": " + a.getBalance() + " cents"
                + (a.getAgentId() == null ? "" : " (agent " + a.getAgentId() + ")");
    }

    private static String line(Transfer t) {
        return "#" + t.getId() + " " + (t.getFromAccount() == null ? "-" : "#" + t.getFromAccount())
                + " -> #" + t.getToAccount() + " " + t.getAmount() + " (" + t.getBy() + ")";
    }
}
