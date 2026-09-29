package com.demo.authservice.support;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Counts attempts per key inside a sliding window, and says when a key has had too many. Failed logins per
 * email and registrations per address are the two users; the map is what keeps a run of guesses from
 * turning into an unbounded one.
 */
public final class AttemptWindow {

    /** Stops the map from growing without bound when someone walks a list of keys through it. */
    private static final int SWEEP_AT = 10_000;

    /** A run of attempts for one key, and when that run started. */
    private record Run(int count, Instant since) {}

    private final Map<String, Run> runs = new ConcurrentHashMap<>();

    private final int max;

    private final Duration window;

    public AttemptWindow(int max, Duration window) {
        this.max = max;
        this.window = window;
    }

    /** Whether this key has already used up its attempts inside the window. */
    public boolean exceeded(String key) {
        Run seen = runs.get(key);
        return seen != null && seen.count() >= max && open(seen);
    }

    public void record(String key) {
        // merge is atomic on a ConcurrentHashMap, so parallel attempts on one key still all get counted.
        runs.merge(key, new Run(1, Instant.now()),
                (seen, one) -> open(seen) ? new Run(seen.count() + 1, seen.since()) : one);
        if (runs.size() > SWEEP_AT) {
            runs.values().removeIf(seen -> !open(seen));
        }
    }

    public void clear(String key) {
        runs.remove(key);
    }

    public Duration window() {
        return window;
    }

    private boolean open(Run seen) {
        return seen.since().isAfter(Instant.now().minus(window));
    }
}
