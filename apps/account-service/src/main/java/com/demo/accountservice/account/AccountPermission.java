package com.demo.accountservice.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One grant: this agent may READ or WRITE this account. One row per (account, agent); the owner sets and removes them. */
@Entity
@Table(name = "account_permissions", uniqueConstraints = @UniqueConstraint(columnNames = {"account_id", "agent_id"}))
@Getter
@Setter
@NoArgsConstructor
public class AccountPermission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private Account account;

    @Column(nullable = false)
    private Long agentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Access access;

    public AccountPermission(Account account, Long agentId, Access access) {
        this.account = account;
        this.agentId = agentId;
        this.access = access;
    }
}
