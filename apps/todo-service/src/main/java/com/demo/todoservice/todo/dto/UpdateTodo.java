package com.demo.todoservice.todo.dto;

/** Edit-todo request body. Both fields are optional: a null one leaves that part of the todo alone. */
public record UpdateTodo(String title, Boolean done) {}
