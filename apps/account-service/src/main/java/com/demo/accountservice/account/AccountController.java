package com.demo.accountservice.account;

import com.demo.accountservice.account.dto.*;
import com.demo.auth.client.Caller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Every endpoint here is private: who is asking comes from the caller's token, never from the request body,
 * and what that answer is allowed to reach is {@link AccountService}'s decision rather than this class's.
 *
 * <p>Only a user's own tokens are accepted: {@link com.demo.auth.client.CallerResolver} answers an agent token
 * with 403 before any method here runs. An agent token reaches the accounts over {@code /mcp} through
 * agent-service, where its owner's READ/WRITE setting and activity log apply; let in here, it would skip both.
 *
 * <p>Under {@code /api/bank} rather than {@code /api/accounts}, which is auth-service's login accounts.
 */
@RestController
@RequestMapping("/api/bank/accounts")
public class AccountController {

    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    public List<AccountResponse> list(Caller caller) {
        return accounts.list(caller).stream().map(AccountResponse::of).toList();
    }

    @PostMapping
    public AccountResponse create(Caller caller, @RequestBody NewAccount in) {
        return AccountResponse.of(accounts.create(caller, in));
    }

    @GetMapping("/{id}")
    public AccountResponse one(Caller caller, @PathVariable Long id) {
        return AccountResponse.of(accounts.get(caller, id));
    }

    @PostMapping("/{id}/deposit")
    public TransferResponse deposit(Caller caller, @PathVariable Long id, @RequestBody Deposit in) {
        return TransferResponse.of(accounts.deposit(caller, id, in.amount()));
    }

    /** Moves money out of the account in the path into {@code to}. */
    @PostMapping("/{id}/transfers")
    public TransferResponse transfer(Caller caller, @PathVariable Long id, @RequestBody NewTransfer in) {
        return TransferResponse.of(accounts.transfer(caller, id, in.to(), in.amount()));
    }

    @GetMapping("/{id}/transfers")
    public List<TransferResponse> transfers(Caller caller, @PathVariable Long id) {
        return accounts.transfers(caller, id).stream().map(TransferResponse::of).toList();
    }

    @PutMapping("/{id}/permissions/{agentId}")
    public AccountResponse setPermission(Caller caller, @PathVariable Long id, @PathVariable Long agentId,
                                         @RequestBody SetPermission in) {
        return AccountResponse.of(accounts.setPermission(caller, id, agentId, in.access()));
    }

    @DeleteMapping("/{id}/permissions/{agentId}")
    public AccountResponse removePermission(Caller caller, @PathVariable Long id, @PathVariable Long agentId) {
        return AccountResponse.of(accounts.removePermission(caller, id, agentId));
    }
}
