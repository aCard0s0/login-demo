package com.demo.todoservice.todo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One todo, owned by the id of the account that created it. The id rather than the email, because an account
 * can change its email and its todos must not be orphaned when it does.
 */
@Entity
@Table(name = "todos")
@Getter
@Setter
@NoArgsConstructor
public class Todo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String owner;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private boolean done;

    public Todo(String owner, String title) {
        this.owner = owner;
        this.title = title;
        this.done = false;
    }
}
