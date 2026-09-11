package com.demo.todoservice;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Every query is scoped by owner, so there is no method here that could return
 * or touch another account's row even if a caller passed someone else's id.
 */
public interface TodoRepository extends JpaRepository<Todo, Long> {

    List<Todo> findByOwnerOrderByIdAsc(String owner);

    Optional<Todo> findByIdAndOwner(Long id, String owner);

    long deleteByIdAndOwner(Long id, String owner);
}
