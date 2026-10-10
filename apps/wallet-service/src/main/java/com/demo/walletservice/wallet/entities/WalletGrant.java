package com.demo.walletservice.wallet.entities;

import com.demo.walletservice.wallet.dto.Access;
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

/** One grant: this agent may READ or WRITE this wallet. One row per (wallet, agent); the owner sets and removes them. */
@Entity
@Table(name = "wallet_grants", uniqueConstraints = @UniqueConstraint(columnNames = {"wallet_id", "agent_id"}))
@Getter
@Setter
@NoArgsConstructor
public class WalletGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private Wallet wallet;

    @Column(nullable = false)
    private Long agentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Access access;

    public WalletGrant(Wallet wallet, Long agentId, Access access) {
        this.wallet = wallet;
        this.agentId = agentId;
        this.access = access;
    }
}
