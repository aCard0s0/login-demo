package com.demo.authservice.session;

import com.demo.authservice.account.Account;

/** A live login: the bearer token plus the account it belongs to. */
public record Session(String token, Account account) {}
