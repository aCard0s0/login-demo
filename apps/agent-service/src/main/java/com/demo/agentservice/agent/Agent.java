package com.demo.agentservice.agent;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * One agent, owned by the id of the account that created it, with the MCP servers it may use. The servers
 * live inside the agent rather than behind their own repository, so the one owner check on the agent covers
 * them too.
 */
@Entity
@Table(name = "agents")
@Getter
@Setter
@NoArgsConstructor
public class Agent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String owner;

    @Column(nullable = false)
    private String name;

    /** Its system prompt. */
    @Column(nullable = false, length = 8000)
    private String instructions;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OthersAccess othersAccess;

    // Eager: every response renders the servers, and open-in-view is off so there is no session left to load them lazily.
    @OneToMany(mappedBy = "agent", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("id ASC")
    private List<AgentMcpServer> servers = new ArrayList<>();

    public Agent(String owner, String name, String instructions, OthersAccess othersAccess) {
        this.owner = owner;
        this.name = name;
        this.instructions = instructions;
        this.othersAccess = othersAccess;
    }
}
