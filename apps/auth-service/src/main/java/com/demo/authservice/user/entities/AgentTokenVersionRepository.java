package com.demo.authservice.user.entities;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentTokenVersionRepository extends JpaRepository<AgentTokenVersion, Long> {

    List<AgentTokenVersion> findByVersionGreaterThan(int version);
}
