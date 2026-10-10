package com.demo.authservice.user.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

/**
 * The token version of one agent, stamped into its tokens as {@code agentVer} and bumped when its owner revokes
 * them. One row per agent that has ever been revoked; an agent with no row is at version zero.
 *
 * <p>Kept here rather than in agent-service because every token version lives on the minting side: this service
 * stamps the claim and publishes the feed the others poll, so the other services never have to ask anyone new.
 * It knows nothing about the agent itself -- only agent-service does -- just its id and a counter.
 */
@Entity
@Table(name = "agent_token_versions")
@Getter
@Setter
@NoArgsConstructor
public class AgentTokenVersion {

    @Id
    private Long agentId;

    @Column(nullable = false)
    @ColumnDefault("0")
    private int version;

    public AgentTokenVersion(Long agentId) {
        this.agentId = agentId;
    }
}
