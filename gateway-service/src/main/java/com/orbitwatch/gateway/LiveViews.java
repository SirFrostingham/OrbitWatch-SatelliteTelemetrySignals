package com.orbitwatch.gateway;

import com.orbitwatch.contracts.AlertEvent;
import com.orbitwatch.contracts.IngestRun;
import com.orbitwatch.contracts.StateVectorEvent;
import com.orbitwatch.contracts.TickStats;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * In-memory materialised views fed by the Kafka listeners, plus reactive sinks that fan each
 * update out to SSE / GraphQL subscribers. Bounded everywhere: latest vector per object, a ring
 * of recent ticks, a ring of recent alerts.
 */
@Component
public class LiveViews {
    private static final int STATS_HISTORY = 720;   // 2 h at a 10 s tick
    private static final int ALERT_HISTORY = 2000;

    private final Map<Integer, StateVectorEvent> latest = new ConcurrentHashMap<>(32_000);
    private final AtomicReference<Instant> currentTick = new AtomicReference<>(Instant.EPOCH);
    private final AtomicLong vectorsSeen = new AtomicLong();
    private final Deque<TickStats> stats = new ArrayDeque<>();
    private final Deque<AlertEvent> alerts = new ArrayDeque<>();
    private final Map<AlertEvent.AlertType, AtomicLong> alertCounts = new EnumMap<>(AlertEvent.AlertType.class);
    private final Deque<IngestRun> ingestRuns = new ArrayDeque<>();

    private final Sinks.Many<TickStats> statsSink = Sinks.many().multicast().onBackpressureBuffer(64, false);
    private final Sinks.Many<AlertEvent> alertSink = Sinks.many().multicast().onBackpressureBuffer(256, false);
    private final Sinks.Many<Instant> tickSink = Sinks.many().multicast().onBackpressureBuffer(8, false);

    public LiveViews() {
        for (AlertEvent.AlertType t : AlertEvent.AlertType.values()) alertCounts.put(t, new AtomicLong());
    }

    // ---- writers (Kafka listeners) ----

    public void onVector(StateVectorEvent v) {
        latest.merge(v.noradId(), v, (old, nu) -> nu.tick().isAfter(old.tick()) ? nu : old);
        vectorsSeen.incrementAndGet();
        Instant prev = currentTick.get();
        if (v.tick().isAfter(prev) && currentTick.compareAndSet(prev, v.tick())) {
            tickSink.tryEmitNext(v.tick());
        }
    }

    public synchronized void onStats(TickStats s) {
        TickStats last = stats.peekLast();
        if (last != null && last.tick().equals(s.tick())) stats.removeLast(); // same tick re-published: keep the latest
        stats.addLast(s);
        while (stats.size() > STATS_HISTORY) stats.removeFirst();
        statsSink.tryEmitNext(s);
    }

    public synchronized void onAlert(AlertEvent a) {
        alerts.addFirst(a);
        while (alerts.size() > ALERT_HISTORY) alerts.removeLast();
        alertCounts.get(a.type()).incrementAndGet();
        alertSink.tryEmitNext(a);
    }

    public synchronized void onIngestRun(IngestRun r) {
        ingestRuns.addFirst(r);
        while (ingestRuns.size() > 50) ingestRuns.removeLast();
    }

    // ---- readers ----

    public Optional<StateVectorEvent> vector(int noradId) { return Optional.ofNullable(latest.get(noradId)); }
    public Collection<StateVectorEvent> vectors() { return latest.values(); }
    public int trackedCount() { return latest.size(); }
    public Instant currentTick() { return currentTick.get(); }
    public long vectorsSeen() { return vectorsSeen.get(); }

    public synchronized Optional<TickStats> latestStats() { return Optional.ofNullable(stats.peekLast()); }
    public synchronized List<TickStats> statsHistory(int last) {
        List<TickStats> all = new ArrayList<>(stats);
        return all.subList(Math.max(0, all.size() - last), all.size());
    }

    public synchronized List<AlertEvent> alerts(AlertEvent.AlertType type, AlertEvent.Severity minSeverity, Integer noradId, int limit) {
        List<AlertEvent> out = new ArrayList<>(Math.min(limit, alerts.size()));
        for (AlertEvent a : alerts) {
            if (type != null && a.type() != type) continue;
            if (minSeverity != null && a.severity().ordinal() < minSeverity.ordinal()) continue;
            if (noradId != null && a.primaryNoradId() != noradId && !noradId.equals(a.secondaryNoradId())) continue;
            out.add(a);
            if (out.size() >= limit) break;
        }
        return out;
    }

    public Map<String, Long> alertCounts() {
        Map<String, Long> m = new java.util.LinkedHashMap<>();
        alertCounts.forEach((k, v) -> m.put(k.name(), v.get()));
        return m;
    }

    public synchronized List<IngestRun> ingestRuns() { return List.copyOf(ingestRuns); }

    /** Objects sorted by a comparator, filtered — used by REST + GraphQL list endpoints. */
    public List<StateVectorEvent> query(String regime, String owner, String type, String nameQuery, int limit, Comparator<StateVectorEvent> order) {
        String q = nameQuery == null ? null : nameQuery.toUpperCase();
        return latest.values().stream()
                .filter(v -> regime == null || regime.equalsIgnoreCase(v.regime()))
                .filter(v -> owner == null || owner.equalsIgnoreCase(v.owner()))
                .filter(v -> type == null || type.equalsIgnoreCase(v.objectType()))
                .filter(v -> q == null || (v.objectName() != null && v.objectName().toUpperCase().contains(q)) || String.valueOf(v.noradId()).equals(q))
                .sorted(order)
                .limit(limit)
                .toList();
    }

    // ---- streams ----
    public Flux<TickStats> statsFlux() { return statsSink.asFlux(); }
    public Flux<AlertEvent> alertFlux() { return alertSink.asFlux(); }
    public Flux<Instant> tickFlux() { return tickSink.asFlux(); }
}
