package com.demo.authservice.user.dto;

import com.demo.authservice.user.entities.Role;
import com.demo.authservice.user.entities.User;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A registered user as the frontend sees it. No password field, hashed or otherwise. {@code token} is present on
 * the one answer that carries a fresh one -- the caller's own edit -- and absent everywhere else.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserResponse(Long id, String name, String email, Role role, boolean suspended, String token) {

    public static UserResponse of(User user) {
        return of(user, null);
    }

    public static UserResponse of(User user, String token) {
        return new UserResponse(user.getId(), user.getName(), user.getEmail(), user.getRole(), user.isSuspended(), token);
    }
}
