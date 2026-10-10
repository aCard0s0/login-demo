package com.demo.walletservice.wallet.dto;

/** What an agent may do with a wallet it does not own: READ sees it and its transfers, WRITE also moves money out of it. */
public enum Access {
    READ, WRITE
}
