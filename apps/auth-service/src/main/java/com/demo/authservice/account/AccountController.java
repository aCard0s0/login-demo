package com.demo.authservice.account;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse register(@RequestBody NewAccount req) {
        return AccountResponse.of(accounts.register(req.name(), req.email(), req.password()));
    }

    /** The caller's own account. Whoever holds the token is the only one who can ask. */
    @GetMapping("/me")
    public AccountResponse me(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return AccountResponse.of(caller(authorization));
    }

    @PutMapping("/me")
    public AccountResponse update(@RequestHeader(value = "Authorization", required = false) String authorization,
                                  @RequestBody UpdateAccount req) {
        Account updated = accounts.update(caller(authorization).getId(),
                req.name(), req.email(), req.currentPassword(), req.newPassword());
        return AccountResponse.of(updated);
    }

    /** Everyone, for the roles that read everyone. A user asking for this gets a 403, not a filtered list. */
    @GetMapping
    public List<AccountResponse> all(@RequestHeader(value = "Authorization", required = false) String authorization) {
        Account caller = caller(authorization);
        if (!caller.getRole().readsEveryone()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "you may only read your own account");
        }
        return accounts.all().stream().map(AccountResponse::of).toList();
    }

    /**
     * Promotes or demotes another account. Admin only, and the only route off USER: registration cannot ask
     * for a role and {@link #update} cannot change one, so this endpoint is the single door.
     */
    @PutMapping("/{id}/role")
    public AccountResponse setRole(@RequestHeader(value = "Authorization", required = false) String authorization,
                                   @PathVariable Long id,
                                   @RequestBody RoleChange req) {
        Account caller = caller(authorization);
        if (caller.getRole() != Role.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only an admin can change a role");
        }
        return AccountResponse.of(accounts.changeRole(caller, id, req.role()));
    }

    /**
     * The account behind the Authorization header, or a 401. There is no logout endpoint to pair with this:
     * a signed token is good until it expires, so logging out is the client dropping the token it holds.
     *
     * <p>The token travels in the header rather than a query parameter so it stays out of access logs and
     * Referer headers; callers send it the same way on every endpoint. The role is read from the account
     * rather than from the token's claims, so a demotion bites at once instead of at the next login.
     */
    private Account caller(String authorization) {
        String token = authorization == null ? "" : authorization.replaceFirst("(?i)^Bearer ", "");
        return accounts.byToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid or expired token"));
    }
}
