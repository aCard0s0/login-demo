package com.demo.todoservice.todo;


/** One todo as the frontend sees it. The owner is deliberately not included; a caller only ever sees its own. */
public record TodoResponse(Long id, String title, boolean done) {

    public static TodoResponse of(Todo todo) {
        return new TodoResponse(todo.getId(), todo.getTitle(), todo.isDone());
    }
}
