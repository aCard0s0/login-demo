package com.demo.authservice.session;

import com.demo.authservice.account.Account;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class SessionController {

    private final SessionService sessions;

    public SessionController(SessionService sessions) {
        this.sessions = sessions;
    }

    @PostMapping("/api/login")
    public LoginResponse login(@RequestBody LoginRequest req) {
        Session session = sessions.login(req.email(), req.password())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid credentials"));
        Account account = session.account();
        return new LoginResponse(session.token(), account.getName(), account.getEmail(), account.getRole());
    }
}
