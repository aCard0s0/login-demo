package com.demo.accountservice.account.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** One movement of money, kept forever. A deposit has no source account. {@code by} names who asked: a user or one of their agents. */
@Entity
@Table(name = "transfers")
@Getter
@NoArgsConstructor
public class Transfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long fromAccount;

    @Column(nullable = false)
    private Long toAccount;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false)
    private String by;

    @Column(nullable = false)
    private Instant at;

    public Transfer(Long fromAccount, Long toAccount, long amount, String by) {
        this.fromAccount = fromAccount;
        this.toAccount = toAccount;
        this.amount = amount;
        this.by = by;
        this.at = Instant.now();
    }
}
