package com.demo.authservice.user.dto;

import com.demo.authservice.user.entities.Role;
import com.demo.authservice.user.entities.User;

/** A registered user as the frontend sees it. No password field, hashed or otherwise. */
public record UserResponse(Long id, String name, String email, Role role, boolean suspended) {

    public static UserResponse of(User user) {
        return new UserResponse(user.getId(), user.getName(), user.getEmail(), user.getRole(),
                user.isSuspended());
    }
}
