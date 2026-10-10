package com.demo.authservice.support;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;

import java.sql.SQLException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Plain JUnit: the advice reads the driver's wording, so it is fed each driver's real wording. */
class AuthExceptionAdviceTests {

    private final AuthExceptionAdvice advice = new AuthExceptionAdvice();

    @Test
    void onlyAnEmailCollisionIsReportedAsOne() {
        assertDuplicateEmail("ERROR: duplicate key value violates unique constraint \"uk_users_email\"\n"
                + "  Detail: Key (email)=(ada@example.com) already exists.");
        assertDuplicateEmail("[SQLITE_CONSTRAINT_UNIQUE] A UNIQUE constraint failed (UNIQUE constraint failed: users.email)");

        ResponseEntity<?> other = advice.duplicate(violation(
                "[SQLITE_CONSTRAINT_PRIMARYKEY] UNIQUE constraint failed: agent_token_versions.agent_id"));
        assertEquals(409, other.getStatusCode().value(), "any other constraint must not claim an email was taken");
        assertEquals(Map.of("error", "that change collided with another, try again"), other.getBody());

        assertEquals(409, advice.duplicate(violation(null)).getStatusCode().value(), "a driver with nothing to say is not an email");
    }

    private void assertDuplicateEmail(String driverMessage) {
        ResponseEntity<?> answer = advice.duplicate(violation(driverMessage));
        assertEquals(400, answer.getStatusCode().value(), driverMessage);
        assertEquals(Map.of("error", "that email is already registered"), answer.getBody());
    }

    private static DataIntegrityViolationException violation(String driverMessage) {
        return new DataIntegrityViolationException("could not execute statement", new SQLException(driverMessage));
    }
}
