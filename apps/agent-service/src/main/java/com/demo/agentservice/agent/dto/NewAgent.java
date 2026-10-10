package com.demo.agentservice.agent.dto;

import com.demo.agentservice.agent.entities.OthersAccess;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The limits are the column widths, checked by the service before anything reaches the database. */
public record NewAgent(@NotBlank(message = "name is required") @Size(max = 100, message = "name must be at most 100 characters") String name,
                       @Size(max = 8000, message = "instructions must be at most 8000 characters") String instructions,
                       OthersAccess othersAccess) {}
