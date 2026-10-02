package com.demo.agentservice.agent;

public record NewMcpServer(String name, String url, String authHeader, Boolean forwardCallerToken, Access access) {}
