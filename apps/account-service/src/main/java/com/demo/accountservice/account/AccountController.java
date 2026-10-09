package com.demo.accountservice.account;

import com.demo.accountservice.token.Caller;
import com.demo.accountservice.token.JwtVerifier;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Every endpoint here is private: who is asking comes from the caller's token, never from the request body,
 * and what that answer is allowed to reach is {@link AccountService}'s decision rather than this class's.
 *
 * <p>Only a user's own tokens are accepted. An agent token reaches the accounts over {@code /mcp} through
 * agent-service, where its owner's READ/WRITE setting and activity log apply; let in here, it would skip both.
 *
 * <p>Under {@code /api/bank} rather than {@code /api/accounts}, which is auth-service's login accounts.
 */
@RestController
@RequestMapping("/api/bank/accounts")
public class AccountController {

    private final AccountService accounts;
    private final JwtVerifier jwt;

    public AccountController(AccountService accounts, JwtVerifier jwt) {
        this.accounts = accounts;
        this.jwt = jwt;
    }

    /** The user behind the token, or 401; an agent token is 403 because its way in is MCP, not this API. */
    private Caller user(String authz) {
        Caller caller = jwt.callerOf(authz);
        if (caller.isAgent()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "an agent token can only reach the accounts over MCP");
        }
        return caller;
    }

    @GetMapping
    public List<AccountResponse> list(@RequestHeader(value = "Authorization", required = false) String authz) {
        return accounts.list(user(authz)).stream().map(AccountResponse::of).toList();
    }

    @PostMapping
    public AccountResponse create(@RequestHeader(value = "Authorization", required = false) String authz,
                                  @RequestBody NewAccount in) {
        return AccountResponse.of(accounts.create(user(authz), in));
    }

    @GetMapping("/{id}")
    public AccountResponse one(@RequestHeader(value = "Authorization", required = false) String authz,
                               @PathVariable Long id) {
        return AccountResponse.of(accounts.get(user(authz), id));
    }

    @PostMapping("/{id}/deposit")
    public TransferResponse deposit(@RequestHeader(value = "Authorization", required = false) String authz,
                                    @PathVariable Long id, @RequestBody Deposit in) {
        return TransferResponse.of(accounts.deposit(user(authz), id, in.amount()));
    }

    /** Moves money out of the account in the path into {@code to}. */
    @PostMapping("/{id}/transfers")
    public TransferResponse transfer(@RequestHeader(value = "Authorization", required = false) String authz,
                                     @PathVariable Long id, @RequestBody NewTransfer in) {
        return TransferResponse.of(accounts.transfer(user(authz), id, in.to(), in.amount()));
    }

    @GetMapping("/{id}/transfers")
    public List<TransferResponse> transfers(@RequestHeader(value = "Authorization", required = false) String authz,
                                            @PathVariable Long id) {
        return accounts.transfers(user(authz), id).stream().map(TransferResponse::of).toList();
    }

    @PutMapping("/{id}/permissions/{agentId}")
    public AccountResponse setPermission(@RequestHeader(value = "Authorization", required = false) String authz,
                                         @PathVariable Long id, @PathVariable Long agentId, @RequestBody SetPermission in) {
        return AccountResponse.of(accounts.setPermission(user(authz), id, agentId, in.access()));
    }

    @DeleteMapping("/{id}/permissions/{agentId}")
    public AccountResponse removePermission(@RequestHeader(value = "Authorization", required = false) String authz,
                                            @PathVariable Long id, @PathVariable Long agentId) {
        return AccountResponse.of(accounts.removePermission(user(authz), id, agentId));
    }
}
