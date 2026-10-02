package com.demo.agentservice.agent;

/** What an agent may do to its owner's other agents: nothing, read their setup and history, or change them too. */
public enum OthersAccess {
    NONE, READ, WRITE;

    public boolean reads() {
        return this != NONE;
    }

    public boolean writes() {
        return this == WRITE;
    }
}
