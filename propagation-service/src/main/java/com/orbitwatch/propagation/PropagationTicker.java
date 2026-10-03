package com.orbitwatch.propagation;

import com.orbitwatch.astro.Frames;
import com.orbitwatch.astro.Sgp4;
import com.orbitwatch.contracts.AlertEvent;
import com.orbitwatch.contracts.Json;
import com.orbitwatch.contracts.StateVectorEvent;
import com.orbitwatch.contracts.Topics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The tick loop. Every {@code tickMs}, propagate every object in the catalogue to the same aligned
 * instant and publish a {@link StateVectorEvent} per object.
 *
 * <p>Ticks are aligned to wall-clock multiples of the interval (e.g. :00, :10, :20 s) so that
 * every propagation instance, and every restart, produces vectors for identical instants — the
 * analytics windows key on the tick timestamp, and a competing/standby instance would produce
 * duplicate rather than skewed data.
 *
 * <p>Work is spread over the common ForkJoin pool; SGP4 for ~16k objects is ~30–60 ms of CPU on a
 * laptop core, so the bottleneck is producing 16k Kafka records, which batching absorbs.
 */
@Component
public class PropagationTicker {
    private static final Logger log = LoggerFactory.getLogger(PropagationTicker.class);

    private final Catalog catalog;
    private final KafkaTemplate<String, byte[]> kafka;
    private final long tickMs;
    private final AtomicBoolean running = new AtomicBoolean();
    private final Counter propagated, failed;
    private final Timer tickTimer;
    private final Set<Integer> failureAlerted = ConcurrentHashMap.newKeySet();
    private final AtomicLong lastTickEpochMs = new AtomicLong();
    private final AtomicInteger lastTickCount = new AtomicInteger();
    private final long settleMs;
    private final int maxDeferrals;
    private int deferrals;

    public PropagationTicker(Catalog catalog, KafkaTemplate<String, byte[]> kafka,
                             @Value("${orbitwatch.propagation.tick-ms:10000}") long tickMs, MeterRegistry meters) {
        this(catalog, kafka, tickMs, 1500, 3, meters);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PropagationTicker(Catalog catalog, KafkaTemplate<String, byte[]> kafka,
                             @Value("${orbitwatch.propagation.tick-ms:10000}") long tickMs,
                             @Value("${orbitwatch.propagation.settle-ms:1500}") long settleMs,
                             @Value("${orbitwatch.propagation.max-deferrals:3}") int maxDeferrals,
                             MeterRegistry meters) {
        this.catalog = catalog;
        this.kafka = kafka;
        this.tickMs = tickMs;
        this.settleMs = settleMs;
        this.maxDeferrals = maxDeferrals;
        this.propagated = meters.counter("orbitwatch.propagation.vectors");
        this.failed = meters.counter("orbitwatch.propagation.failures");
        this.tickTimer = meters.timer("orbitwatch.propagation.tick");
        meters.gauge("orbitwatch.propagation.catalog_size", catalog, Catalog::size);
        meters.gauge("orbitwatch.propagation.last_tick_objects", lastTickCount, AtomicInteger::get);
    }

    public Instant lastTick() { return Instant.ofEpochMilli(lastTickEpochMs.get()); }
    public int lastTickCount() { return lastTickCount.get(); }

    @Scheduled(fixedRateString = "${orbitwatch.propagation.tick-ms:10000}", initialDelayString = "${orbitwatch.propagation.initial-delay-ms:5000}")
    public void tick() {
        if (!running.compareAndSet(false, true)) {
            log.warn("previous tick still running; skipping");
            return;
        }
        try {
            Instant now = Instant.now();
            if (shouldDefer(now.toEpochMilli())) return;
            Instant tick = alignedTick(now, tickMs);
            if (tick.toEpochMilli() == lastTickEpochMs.get()) return; // already produced this instant
            tickTimer.record(() -> propagateAll(tick));
            lastTickEpochMs.set(tick.toEpochMilli());
        } finally {
            running.set(false);
        }
    }

    /**
     * Don't publish a half-loaded catalogue as if it were a complete snapshot. While element sets are
     * still arriving (the compacted topic loading after a restart, or an ingest burst), defer the
     * tick; bounded by {@code maxDeferrals} so a continuous update stream can never starve ticks.
     * (Seen 2026-09-10: the first tick after start published 11,457 of 16,506 objects.)
     */
    synchronized boolean shouldDefer(long nowMs) {
        long last = catalog.lastElementAppliedAtMs();
        boolean settling = last > 0 && nowMs - last < settleMs;
        if (settling && deferrals < maxDeferrals) {
            deferrals++;
            log.info("element sets still arriving (catalog {} objects); deferring tick {}/{}", catalog.size(), deferrals, maxDeferrals);
            return true;
        }
        if (settling) log.warn("element sets still arriving after {} deferrals; ticking with catalog of {} objects", deferrals, catalog.size());
        deferrals = 0;
        return false;
    }

    static Instant alignedTick(Instant now, long tickMs) {
        long ms = now.toEpochMilli();
        return Instant.ofEpochMilli(ms - Math.floorMod(ms, tickMs));
    }

    private void propagateAll(Instant tick) {
        if (catalog.size() == 0) {
            log.info("catalog empty; waiting for element sets");
            return;
        }
        AtomicInteger ok = new AtomicInteger(), bad = new AtomicInteger();
        catalog.all().parallelStream().forEach(obj -> {
            if (obj.hasFailed()) return;
            StateVectorEvent ev = propagateOne(obj, tick);
            if (ev == null) { bad.incrementAndGet(); return; }
            kafka.send(Topics.STATE_VECTORS, String.valueOf(ev.noradId()), Json.write(ev));
            ok.incrementAndGet();
        });
        kafka.flush();
        propagated.increment(ok.get());
        failed.increment(bad.get());
        lastTickCount.set(ok.get());
        if (log.isDebugEnabled() || bad.get() > 0) {
            log.info("tick {}: propagated={} failed={} catalog={}", tick, ok.get(), bad.get(), catalog.size());
        }
    }

    /** Propagate one object to the tick instant; null on failure (a PROPAGATION_FAILURE alert is emitted once). */
    StateVectorEvent propagateOne(TrackedObject obj, Instant tick) {
        try {
            double tsince = Frames.minutesSince(obj.elements().epoch(), tick);
            Sgp4.StateVector sv = obj.propagator().propagate(tsince);
            Frames.Geodetic geo = Frames.temeToGeodetic(sv.x(), sv.y(), sv.z(), tick);
            return new StateVectorEvent(obj.noradId(), obj.name(), obj.objectType(), obj.owner(), obj.regime().name(),
                    tick, obj.elements().epoch(), tsince / 60.0,
                    sv.x(), sv.y(), sv.z(), sv.vx(), sv.vy(), sv.vz(),
                    geo.latDeg(), geo.lonDeg(), geo.altKm(), sv.speedKmS());
        } catch (Sgp4.PropagationException ex) {
            obj.markFailed();
            if (failureAlerted.add(obj.noradId())) {
                AlertEvent alert = new AlertEvent("propfail:" + obj.noradId() + ":" + obj.elements().fingerprint(),
                        AlertEvent.AlertType.PROPAGATION_FAILURE, AlertEvent.Severity.INFO, tick,
                        obj.noradId(), obj.name(), null, null,
                        "Propagation failed for " + obj.name() + " — likely decayed (" + ex.getMessage() + ")",
                        Map.of("code", ex.code, "epoch", obj.elements().epoch().toString()),
                        "propfail:" + obj.noradId());
                kafka.send(Topics.ALERTS, alert.alertId(), Json.write(alert));
            }
            return null;
        } catch (RuntimeException ex) {
            log.warn("unexpected propagation error for {}: {}", obj.noradId(), ex.toString());
            obj.markFailed();
            return null;
        }
    }

    public Duration tickInterval() { return Duration.ofMillis(tickMs); }
}
