package com.demo.agentservice.run;

import com.demo.agentservice.token.Caller;
import com.demo.agentservice.token.JwtVerifier;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Starts a run. The caller's own token is what the agent carries to servers that want one. */
@RestController
public class RunController {

    private final AgentRunner runner;
    private final JwtVerifier jwt;

    public RunController(AgentRunner runner, JwtVerifier jwt) {
        this.runner = runner;
        this.jwt = jwt;
    }

    @PostMapping("/api/agents/{id}/run")
    public RunResponse run(@RequestHeader(value = "Authorization", required = false) String authz,
                           @PathVariable Long id, @RequestBody RunRequest in) {
        Caller caller = jwt.callerOf(authz);
        String bearer = authz.replaceFirst("(?i)^Bearer ", "");
        return runner.run(caller, bearer, id, in.prompt());
    }
}
