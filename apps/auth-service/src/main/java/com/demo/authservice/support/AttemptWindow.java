package com.demo.authservice.support;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts attempts per key inside a sliding window, and says when a key has had too many. Failed logins per
 * email and registrations per address are the two users; the cap on keys is what keeps a run of guesses from
 * turning into an unbounded one.
 */
public final class AttemptWindow {

    /**
     * The most keys kept. 100k runs is about 10 MB; reaching it through login means sustaining over a hundred
     * BCrypt rounds a second for a whole window, which is CPU-bound long before it is memory-bound.
     */
    static final int MAX_KEYS = 100_000;

    /** Expired runs are swept at most this often once the map is full, never on every attempt. */
    private static final Duration SWEEP_EVERY = Duration.ofSeconds(1);

    /** A run of attempts for one key, and when that run started. */
    private record Run(int count, Instant since) {}

    private final Map<String, Run> runs = new ConcurrentHashMap<>();

    private final AtomicLong lastSweepMillis = new AtomicLong();

    private final int max;

    private final Duration window;

    private final int maxKeys;

    public AttemptWindow(int max, Duration window) {
        this(max, window, MAX_KEYS);
    }

    AttemptWindow(int max, Duration window, int maxKeys) {
        this.max = max;
        this.window = window;
        this.maxKeys = maxKeys;
    }

    /** Whether this key has already used up its attempts inside the window. */
    public boolean exceeded(String key) {
        Run seen = runs.get(key);
        return seen != null && seen.count() >= max && open(seen);
    }

    public void record(String key) {
        if (runs.size() >= maxKeys) {
            sweep();
            // ponytail: full of live runs, so a key not yet seen goes uncounted rather than growing the map
            // without bound. Known keys keep counting. Fail-open on purpose: fail-closed here would let whoever
            // filled the map lock everybody else out of logging in. The upgrade is an LRU with eviction.
            if (runs.size() >= maxKeys && !runs.containsKey(key)) {
                return;
            }
        }
        // merge is atomic on a ConcurrentHashMap, so parallel attempts on one key still all get counted.
        runs.merge(key, new Run(1, Instant.now()),
                (seen, one) -> open(seen) ? new Run(seen.count() + 1, seen.since()) : one);
    }

    public void clear(String key) {
        runs.remove(key);
    }

    public Duration window() {
        return window;
    }

    /** Drops expired runs, at most once per {@link #SWEEP_EVERY}: a full map must not cost a scan per attempt. */
    private void sweep() {
        long now = System.currentTimeMillis();
        long last = lastSweepMillis.get();
        if (now - last >= SWEEP_EVERY.toMillis() && lastSweepMillis.compareAndSet(last, now)) {
            runs.values().removeIf(seen -> !open(seen));
        }
    }

    private boolean open(Run seen) {
        return seen.since().isAfter(Instant.now().minus(window));
    }
}
