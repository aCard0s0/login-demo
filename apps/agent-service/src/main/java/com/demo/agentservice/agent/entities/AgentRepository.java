package com.demo.agentservice.agent.entities;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Every query is scoped to an owner in the query itself, so no caller can reach another account's agent by
 * passing someone else's id. There is no unscoped read: no role sees everybody's agents.
 */
public interface AgentRepository extends JpaRepository<Agent, Long> {

    List<Agent> findByOwnerOrderByIdAsc(String owner);

    Optional<Agent> findByIdAndOwner(Long id, String owner);
}
