package com.demo.authservice.user.entities;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface AgentTokenVersionRepository extends JpaRepository<AgentTokenVersion, Long> {

    /** The two columns the revocation feed serves. */
    record AgentVersion(Long agentId, int version) {}

    List<AgentVersion> findByVersionGreaterThan(int version);

    /** First revoke inserts at 1, every later one adds 1: atomic on Postgres and SQLite alike. Caller owns the transaction. */
    @Modifying(clearAutomatically = true)
    @Query(value = "insert into agent_token_versions (agent_id, version) values (:agentId, 1) "
            + "on conflict (agent_id) do update set version = agent_token_versions.version + 1", nativeQuery = true)
    void bump(Long agentId);
}
