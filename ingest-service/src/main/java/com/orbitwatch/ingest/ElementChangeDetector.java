package com.orbitwatch.ingest;

import com.orbitwatch.contracts.GpElementSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers the last published fingerprint per object so a bulk snapshot becomes a delta stream.
 * Warmed from the compacted {@code gp-elements} topic at startup — Kafka is the source of truth,
 * this map is just a cache of it.
 */
public final class ElementChangeDetector {
    private final Map<Integer, String> lastFingerprint = new ConcurrentHashMap<>();

    public void remember(GpElementSet e) {
        if (e.fingerprint() != null) lastFingerprint.put(e.noradId(), e.fingerprint());
    }

    public enum Outcome { NEW, CHANGED, UNCHANGED }

    public Outcome classify(GpElementSet e) {
        String prev = lastFingerprint.get(e.noradId());
        if (prev == null) return Outcome.NEW;
        return prev.equals(e.fingerprint()) ? Outcome.UNCHANGED : Outcome.CHANGED;
    }

    public int size() { return lastFingerprint.size(); }

    public boolean isKnown(int noradId) { return lastFingerprint.containsKey(noradId); }
}
