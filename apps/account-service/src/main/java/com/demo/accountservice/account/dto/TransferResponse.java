package com.demo.accountservice.account.dto;

import com.demo.accountservice.account.entities.Transfer;

import java.time.Instant;

/** One transfer. {@code from} is null for a deposit. */
public record TransferResponse(Long id, Long from, Long to, long amount, String by, Instant at) {

    public static TransferResponse of(Transfer t) {
        return new TransferResponse(t.getId(), t.getFromAccount(), t.getToAccount(), t.getAmount(), t.getBy(), t.getAt());
    }
}
