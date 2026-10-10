package com.demo.authservice.user;

import com.demo.authservice.user.dto.NewUser;
import com.demo.authservice.user.dto.RoleChange;
import com.demo.authservice.user.dto.Suspension;
import com.demo.authservice.user.dto.UpdateUser;
import com.demo.authservice.user.dto.UserResponse;
import com.demo.authservice.user.entities.Role;
import com.demo.authservice.user.entities.User;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Who is asking is the {@link User} parameter, resolved from the Authorization header by
 * {@link CurrentUserResolver} before any method here runs; registration is the one endpoint without it.
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService users;

    public UserController(UserService users) {
        this.users = users;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@RequestBody NewUser req) {
        return UserResponse.of(users.register(req.name(), req.email(), req.password()));
    }

    /** The caller themselves. Whoever holds the token is the only one who can ask. */
    @GetMapping("/me")
    public UserResponse me(User caller) {
        return UserResponse.of(caller);
    }

    /**
     * Edits the caller. The answer carries a fresh token: a new password revokes every token the user held,
     * this one included, and the page swaps to the new one rather than being signed out mid-edit.
     */
    @PutMapping("/me")
    public UserResponse update(User caller, @RequestBody UpdateUser req) {
        User updated = users.update(caller.getId(), req.name(), req.email(), req.currentPassword(), req.newPassword());
        return UserResponse.of(updated, users.issue(updated));
    }

    /** Everyone, for the roles that read everyone. A user asking for this gets a 403, not a filtered list. */
    @GetMapping
    public List<UserResponse> all(User caller) {
        if (!caller.getRole().readsEveryone()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "you may only read yourself");
        }
        return users.all().stream().map(UserResponse::of).toList();
    }

    /**
     * Promotes or demotes another user. Admin only, and the only route off USER: registration cannot ask
     * for a role and {@link #update} cannot change one, so this endpoint is the single door.
     */
    @PutMapping("/{id}/role")
    public UserResponse setRole(User caller, @PathVariable Long id, @RequestBody RoleChange req) {
        return UserResponse.of(users.changeRole(admin(caller), id, req.role()));
    }

    /** Suspends or reactivates another user. Admin only. */
    @PutMapping("/{id}/suspended")
    public UserResponse setSuspended(User caller, @PathVariable Long id, @RequestBody Suspension req) {
        return UserResponse.of(users.setSuspended(admin(caller), id, req.suspended()));
    }

    /** Signs another user out everywhere by killing every token it holds. Admin only. */
    @PostMapping("/{id}/revoke")
    public UserResponse revoke(User caller, @PathVariable Long id) {
        admin(caller);
        return UserResponse.of(users.revokeTokens(id));
    }

    private static User admin(User caller) {
        if (caller.getRole() != Role.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only an admin can do that");
        }
        return caller;
    }
}
