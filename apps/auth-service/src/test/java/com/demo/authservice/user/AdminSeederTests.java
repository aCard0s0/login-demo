package com.demo.authservice.user;

import com.demo.authservice.user.entities.User;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The one branch {@code ensureAdmin}'s own tests cannot reach: no credentials is a warning, not a seed and not a crash. */
class AdminSeederTests {

    @Test
    void missingCredentialsSeedNobodyAndCompleteOnesSeedTheAdmin() {
        UserService users = mock(UserService.class);
        when(users.ensureAdmin(anyString(), anyString())).thenReturn(new User("Admin", "chief@example.com", "hash"));

        new AdminSeeder(users, "", "").run(null);
        new AdminSeeder(users, "chief@example.com", "").run(null);
        new AdminSeeder(users, "", "chief-pass-01").run(null);
        verify(users, never()).ensureAdmin(anyString(), anyString());

        new AdminSeeder(users, "chief@example.com", "chief-pass-01").run(null);
        verify(users).ensureAdmin("chief@example.com", "chief-pass-01");
    }
}
