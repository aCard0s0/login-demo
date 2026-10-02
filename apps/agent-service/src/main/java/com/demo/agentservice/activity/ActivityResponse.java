package com.demo.agentservice.activity;

import java.time.Instant;

public record ActivityResponse(Long id, Instant at, String kind, String detail) {

    public static ActivityResponse of(Activity a) {
        return new ActivityResponse(a.getId(), a.getAt(), a.getKind(), a.getDetail());
    }
}
