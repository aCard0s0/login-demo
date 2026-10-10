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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

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
    public UserResponse me(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return UserResponse.of(caller(authorization));
    }

    @PutMapping("/me")
    public UserResponse update(@RequestHeader(value = "Authorization", required = false) String authorization,
                                  @RequestBody UpdateUser req) {
        User updated = users.update(caller(authorization).getId(),
                req.name(), req.email(), req.currentPassword(), req.newPassword());
        return UserResponse.of(updated);
    }

    /** Everyone, for the roles that read everyone. A user asking for this gets a 403, not a filtered list. */
    @GetMapping
    public List<UserResponse> all(@RequestHeader(value = "Authorization", required = false) String authorization) {
        User caller = caller(authorization);
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
    public UserResponse setRole(@RequestHeader(value = "Authorization", required = false) String authorization,
                                   @PathVariable Long id,
                                   @RequestBody RoleChange req) {
        return UserResponse.of(users.changeRole(admin(authorization), id, req.role()));
    }

    /** Suspends or reactivates another user. Admin only. */
    @PutMapping("/{id}/suspended")
    public UserResponse setSuspended(@RequestHeader(value = "Authorization", required = false) String authorization,
                                        @PathVariable Long id,
                                        @RequestBody Suspension req) {
        return UserResponse.of(users.setSuspended(admin(authorization), id, req.suspended()));
    }

    /** Signs another user out everywhere by killing every token it holds. Admin only. */
    @PostMapping("/{id}/revoke")
    public UserResponse revoke(@RequestHeader(value = "Authorization", required = false) String authorization,
                                  @PathVariable Long id) {
        admin(authorization);
        return UserResponse.of(users.revokeTokens(id));
    }

    private User admin(String authorization) {
        User caller = caller(authorization);
        if (caller.getRole() != Role.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only an admin can do that");
        }
        return caller;
    }

    /**
     * The user behind the Authorization header, or a 401. There is no logout endpoint to pair with this:
     * a signed token is good until it expires, so logging out is the client dropping the token it holds.
     *
     * <p>The token travels in the header rather than a query parameter so it stays out of access logs and
     * Referer headers; callers send it the same way on every endpoint. The role is read from the user
     * rather than from the token's claims, so a demotion bites at once instead of at the next login.
     */
    private User caller(String authorization) {
        String token = authorization == null ? "" : authorization.replaceFirst("(?i)^Bearer ", "");
        return users.byToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid or expired token"));
    }
}
