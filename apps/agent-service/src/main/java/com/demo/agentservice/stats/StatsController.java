package com.demo.agentservice.stats;

import com.demo.agentservice.agent.AgentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Deliberately unauthenticated, for the landing page and the container healthcheck. Nothing here is scoped to an account. */
@RestController
public class StatsController {

    private final AgentService agents;

    public StatsController(AgentService agents) {
        this.agents = agents;
    }

    @GetMapping("/api/public/agents/stats")
    public PublicStats stats() {
        return new PublicStats(agents.count());
    }
}
