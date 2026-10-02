package com.demo.agentservice.activity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ActivityRepository extends JpaRepository<Activity, Long> {

    List<Activity> findTop100ByAgentIdOrderByIdDesc(Long agentId);

    void deleteByAgentId(Long agentId);
}
