package com.demo.web.errors;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** A 400 with a reason the page can show: {@code throw Bad.request("name is required")}. The one copy of what every service used to spell out itself. */
public final class Bad {

    private Bad() {}

    public static ResponseStatusException request(String why) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, why);
    }
}
