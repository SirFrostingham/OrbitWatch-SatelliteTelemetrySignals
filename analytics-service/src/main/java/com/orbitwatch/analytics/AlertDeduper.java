package com.orbitwatch.analytics;

import com.orbitwatch.contracts.AlertEvent;
import java.time.Duration;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueStore;

/**
 * Stateful processor: passes an alert through only if no alert with the same {@code dedupeKey} was
 * forwarded within the TTL. Uses a Kafka-backed key-value store, so de-duplication survives
 * restarts and rebalances. Old entries are purged lazily on each pass.
 */
public final class AlertDeduper implements Processor<String, AlertEvent, String, AlertEvent> {
    public static final String STORE = "alert-dedupe-store";

    private final long ttlMs;
    private KeyValueStore<String, Long> store;
    private ProcessorContext<String, AlertEvent> ctx;
    private long lastPurge;

    public AlertDeduper(Duration ttl) { this.ttlMs = ttl.toMillis(); }

    @Override
    public void init(ProcessorContext<String, AlertEvent> context) {
        this.ctx = context;
        this.store = context.getStateStore(STORE);
    }

    @Override
    public void process(Record<String, AlertEvent> record) {
        AlertEvent a = record.value();
        if (a == null) return;
        long now = record.timestamp();
        String key = a.dedupeKey() != null ? a.dedupeKey() : a.alertId();
        Long last = store.get(key);
        if (last != null && now - last < ttlMs) return;
        store.put(key, now);
        ctx.forward(record.withKey(a.alertId()));
        if (now - lastPurge > ttlMs) purge(now);
    }

    private void purge(long now) {
        lastPurge = now;
        try (var it = store.all()) {
            while (it.hasNext()) {
                var kv = it.next();
                if (now - kv.value >= ttlMs) store.delete(kv.key);
            }
        }
    }
}
