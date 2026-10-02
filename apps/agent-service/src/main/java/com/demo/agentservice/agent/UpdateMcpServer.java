package com.demo.agentservice.agent;

/** A null field means "leave it alone"; an empty {@code authHeader} clears the stored one. */
public record UpdateMcpServer(String name, String url, String authHeader, Boolean forwardCallerToken, Access access) {}
