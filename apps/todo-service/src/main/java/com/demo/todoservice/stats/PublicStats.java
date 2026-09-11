package com.demo.todoservice.stats;

/** Numbers anyone may see. A total says how busy the demo is without naming anybody. */
public record PublicStats(long todos) {}
