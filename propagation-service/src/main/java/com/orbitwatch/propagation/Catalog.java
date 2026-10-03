package com.orbitwatch.propagation;

import com.orbitwatch.contracts.CatalogObject;
import com.orbitwatch.contracts.GpElementSet;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * In-memory materialised view of the two compacted catalogue topics. Element-set updates replace
 * the TrackedObject (so its SGP4 instance is rebuilt for the new epoch); catalogue updates are
 * merged in. Both listeners may arrive in any order — a catalogue row for an object without
 * elements is parked until its elements show up.
 */
@Component
public class Catalog {
    private final Map<Integer, TrackedObject> objects = new ConcurrentHashMap<>(32_000);
    private final Map<Integer, CatalogObject> pendingCatalog = new ConcurrentHashMap<>();
    private final AtomicLong elementUpdates = new AtomicLong();
    private volatile long lastElementAppliedAtMs;

    public void applyElements(GpElementSet e) {
        objects.compute(e.noradId(), (id, existing) -> {
            CatalogObject c = existing != null ? existing.catalog() : pendingCatalog.remove(id);
            return new TrackedObject(e, c);
        });
        elementUpdates.incrementAndGet();
        lastElementAppliedAtMs = System.currentTimeMillis();
    }

    /** Wall-clock time of the most recent element-set update, 0 if none yet. */
    public long lastElementAppliedAtMs() { return lastElementAppliedAtMs; }

    public void applyCatalog(CatalogObject c) {
        TrackedObject updated = objects.computeIfPresent(c.noradId(), (id, existing) -> existing.withCatalog(c));
        if (updated == null) pendingCatalog.put(c.noradId(), c);
    }

    public Optional<TrackedObject> get(int noradId) { return Optional.ofNullable(objects.get(noradId)); }
    public Collection<TrackedObject> all() { return objects.values(); }
    public int size() { return objects.size(); }
    public long elementUpdates() { return elementUpdates.get(); }
    public int pendingCatalogRows() { return pendingCatalog.size(); }
}
