package com.demo.agentservice.agent.dto;

import com.demo.agentservice.agent.entities.OthersAccess;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** A null field means "leave it alone"; a name that is sent must not be blank. */
public record UpdateAgent(@Pattern(regexp = "(?s).*\\S.*", message = "name is required") @Size(max = 100, message = "name must be at most 100 characters") String name,
                          @Size(max = 8000, message = "instructions must be at most 8000 characters") String instructions,
                          OthersAccess othersAccess) {}
