package com.demo.authservice.support;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plain JUnit: the window has no Spring in it, and the 429 it leads to is covered by the contract tests. */
class AttemptWindowTests {

    @Test
    void refusesAKeyOnceItHasUsedUpTheWindowAndForgetsItAfterwards() throws Exception {
        AttemptWindow window = new AttemptWindow(2, Duration.ofMillis(50));

        window.record("a");
        assertFalse(window.exceeded("a"), "one attempt is not a run");
        window.record("a");
        assertTrue(window.exceeded("a"), "the cap counts inclusive");
        assertFalse(window.exceeded("b"), "keys are counted apart");

        window.clear("a");
        assertFalse(window.exceeded("a"), "a success clears the run");

        window.record("a");
        window.record("a");
        Thread.sleep(60);
        assertFalse(window.exceeded("a"), "an old run expires on its own");
        window.record("a");
        assertFalse(window.exceeded("a"), "and the next attempt starts a fresh one rather than continuing it");
    }
}
