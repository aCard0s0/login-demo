package com.demo.authservice.stats;

import com.demo.authservice.user.UserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Deliberately unauthenticated, for the landing page. Nothing here names a user. */
@RestController
public class StatsController {

    private final UserService users;

    public StatsController(UserService users) {
        this.users = users;
    }

    @GetMapping("/api/public/stats")
    public PublicStats stats() {
        return new PublicStats(users.count());
    }
}
