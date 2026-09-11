package com.demo.todoservice.todo;

/**
 * One todo as the frontend sees it. The owner is included because a role that reads everyone gets a list it
 * could not otherwise make sense of; for everybody else it is their own id, which tells them nothing new.
 */
public record TodoResponse(Long id, String owner, String title, boolean done) {

    public static TodoResponse of(Todo todo) {
        return new TodoResponse(todo.getId(), todo.getOwner(), todo.getTitle(), todo.isDone());
    }
}
