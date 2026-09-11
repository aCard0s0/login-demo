package com.demo.authservice.account;

/** A registered account as the frontend sees it. No password field, hashed or otherwise. */
public record AccountResponse(Long id, String name, String email, Role role) {

    public static AccountResponse of(Account account) {
        return new AccountResponse(account.getId(), account.getName(), account.getEmail(), account.getRole());
    }
}
