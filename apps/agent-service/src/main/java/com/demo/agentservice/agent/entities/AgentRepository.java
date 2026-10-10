package com.demo.agentservice.agent.entities;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Every query is scoped to an owner in the query itself, so no caller can reach another user's agent by
 * passing someone else's id. There is no unscoped read: no role sees everybody's agents.
 *
 * <p>The servers are joined in: eager on the entity only says they must be loaded, and a derived query would
 * load them with one more select per agent.
 */
public interface AgentRepository extends JpaRepository<Agent, Long> {

    @EntityGraph(attributePaths = "servers")
    List<Agent> findByOwnerOrderByIdAsc(String owner);

    @EntityGraph(attributePaths = "servers")
    Optional<Agent> findByIdAndOwner(Long id, String owner);
}
