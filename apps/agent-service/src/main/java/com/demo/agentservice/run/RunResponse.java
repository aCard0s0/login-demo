package com.demo.agentservice.run;

/** The model's final words and how many times it was asked. The detail is in the agent's activity. */
public record RunResponse(String reply, int turns) {}
