package com.demo.authservice.account;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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
        return response(accounts.register(req.name(), req.email(), req.password()));
    }

    /** The caller's own account. Whoever holds the token is the only one who can ask. */
    @GetMapping("/me")
    public AccountResponse me(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return response(caller(authorization));
    }

    @PutMapping("/me")
    public AccountResponse update(@RequestHeader(value = "Authorization", required = false) String authorization,
                                  @RequestBody UpdateAccount req) {
        Account updated = accounts.update(caller(authorization).getId(),
                req.name(), req.email(), req.currentPassword(), req.newPassword());
        return response(updated);
    }

    private static AccountResponse response(Account account) {
        return new AccountResponse(account.getId(), account.getName(), account.getEmail());
    }

    /**
     * The account behind the Authorization header, or a 401. There is no logout endpoint to pair with this:
     * a signed token is good until it expires, so logging out is the client dropping the token it holds.
     *
     * <p>The token travels in the header rather than a query parameter so it stays out of access logs and
     * Referer headers; callers send it the same way on every endpoint.
     */
    private Account caller(String authorization) {
        String token = authorization == null ? "" : authorization.replaceFirst("(?i)^Bearer ", "");
        return accounts.byToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid or expired token"));
    }
}
