package com.demo.agentservice.agent.dto;

import com.demo.agentservice.agent.entities.OthersAccess;

public record NewAgent(String name, String instructions, OthersAccess othersAccess) {}
