package com.demo.agentservice.activity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ActivityRepository extends JpaRepository<Activity, Long> {

    List<Activity> findTop100ByAgentIdOrderByIdDesc(Long agentId);

    /** One statement: a derived delete would load every line as an entity and delete them one by one. */
    @Modifying
    @Query("delete from Activity a where a.agentId = :agentId")
    void deleteByAgentId(Long agentId);
}
