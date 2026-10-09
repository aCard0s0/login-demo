package com.demo.accountservice.account;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * One money account. {@code owner} is always the id of a user; {@code agentId} is set when the account was
 * opened for one of that user's agents, which is then the only agent that owns it. The balance is a count
 * of minor units (cents), never a fraction.
 *
 * <p>The permissions live inside the account rather than behind their own repository, so the one owner check
 * on the account covers them too.
 */
@Entity
@Table(name = "accounts")
@Getter
@Setter
@NoArgsConstructor
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String owner;

    private Long agentId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private long balance;

    // Eager: every response renders the permissions, and open-in-view is off so there is no session left to load them lazily.
    @OneToMany(mappedBy = "account", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("id ASC")
    private List<AccountPermission> permissions = new ArrayList<>();

    public Account(String owner, Long agentId, String name) {
        this.owner = owner;
        this.agentId = agentId;
        this.name = name;
        this.balance = 0;
    }
}
