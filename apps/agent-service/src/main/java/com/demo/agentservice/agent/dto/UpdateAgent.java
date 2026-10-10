package com.demo.agentservice.agent.dto;

import com.demo.agentservice.agent.entities.OthersAccess;

/** A null field means "leave it alone". */
public record UpdateAgent(String name, String instructions, OthersAccess othersAccess) {}
