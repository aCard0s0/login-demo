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

    @Test
    void aFullMapStopsTakingNewKeysButKeepsCountingTheOnesItHas() throws Exception {
        AttemptWindow window = new AttemptWindow(1, Duration.ofMillis(50), 2);

        window.record("a");
        window.record("b");
        window.record("c");
        assertFalse(window.exceeded("c"), "a key past the cap is not kept: fail open, not a lockout for everyone");
        window.record("a");
        assertTrue(window.exceeded("a"), "a key already there still counts");

        Thread.sleep(60);
        // Expired runs are swept at most once a second, so a fresh key gets in only once that second has passed.
        Thread.sleep(1_000);
        window.record("c");
        assertTrue(window.exceeded("c"), "once the old runs have expired and been swept, room is made");
    }
}
