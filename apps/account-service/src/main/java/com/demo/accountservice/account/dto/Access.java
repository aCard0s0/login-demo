package com.demo.accountservice.account.dto;

/** What an agent may do with an account it does not own: READ sees it and its transfers, WRITE also moves money out of it. */
public enum Access {
    READ, WRITE
}
