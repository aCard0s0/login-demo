package com.demo.agentservice.run;

/** A tool call the agent's permissions do not allow. Becomes an error result for the model and a line in the log. */
public class AccessDenied extends RuntimeException {

    public AccessDenied(String message) {
        super(message);
    }
}
