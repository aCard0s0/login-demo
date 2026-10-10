package com.demo.agentservice.agent.dto;

/** A null field means "leave it alone". */
public record UpdateAgent(String name, String instructions, OthersAccess othersAccess) {}
