package com.demo.agentservice.agent;

/** A null field means "leave it alone". */
public record UpdateAgent(String name, String instructions, OthersAccess othersAccess) {}
