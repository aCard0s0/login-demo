package com.demo.todoservice.todo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Every owner-scoped query is scoped in the query itself, so no caller can reach another account's row by
 * passing someone else's id. The two unscoped reads exist for the roles that are allowed to see everything,
 * and {@link TodoService} is the only thing that decides which pair a request gets.
 */
public interface TodoRepository extends JpaRepository<Todo, Long> {

    List<Todo> findByOwnerOrderByIdAsc(String owner);

    /** Every todo there is. Only ever reached by a caller whose role reads everyone. */
    List<Todo> findAllByOrderByIdAsc();

    Optional<Todo> findByIdAndOwner(Long id, String owner);

}
