package com.orbitwatch.analytics;

import com.orbitwatch.contracts.StateVectorEvent;
import com.orbitwatch.contracts.TickStats;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.apache.kafka.streams.processor.PunctuationType;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;

/**
 * Two-stage tick aggregation, the standard cure for a hot key.
 *
 * <p>Naively re-keying every state vector to the single key "tick" funnels 16k records per tick
 * through one partition and one thread, with a state-store read-modify-write per record. Instead:
 * <ol>
 *   <li>{@link Partial} runs on every source partition and folds vectors into an in-memory
 *       accumulator per tick — no serialisation per record. On a wall-clock punctuation it forwards
 *       one partial per tick once the tick is old enough that all its vectors have arrived.</li>
 *   <li>{@link Merge} runs on the single "tick" partition and merges the handful of partials
 *       (one per source partition) into the final {@link TickStats}.</li>
 * </ol>
 * The accumulators are in-memory only: a crash loses at most the ticks in flight, and the next tick
 * regenerates the statistics — an explicit "derived data, at-least-once, cheap to recompute" choice.
 */
final class TickStatsProcessors {
    private TickStatsProcessors() {}

    /** Stage 1: per-partition partial accumulators. Emits (tickIso, TickAccumulator). */
    static final class Partial implements Processor<String, StateVectorEvent, String, TickAccumulator> {
        private final long tickMs, graceMs;
        private final double staleHours, lowPerigeeKm;
        private final Map<Long, TickAccumulator> open = new HashMap<>();
        private ProcessorContext<String, TickAccumulator> ctx;

        Partial(Duration tick, Duration grace, double staleHours, double lowPerigeeKm) {
            this.tickMs = tick.toMillis();
            this.graceMs = grace.toMillis();
            this.staleHours = staleHours;
            this.lowPerigeeKm = lowPerigeeKm;
        }

        @Override
        public void init(ProcessorContext<String, TickAccumulator> context) {
            this.ctx = context;
            context.schedule(Duration.ofSeconds(1), PunctuationType.WALL_CLOCK_TIME, this::flush);
        }

        @Override
        public void process(Record<String, StateVectorEvent> r) {
            StateVectorEvent v = r.value();
            if (v == null || v.tick() == null) return;
            open.computeIfAbsent(v.tick().toEpochMilli(), k -> new TickAccumulator()).add(v, staleHours, lowPerigeeKm);
        }

        private void flush(long now) {
            Iterator<Map.Entry<Long, TickAccumulator>> it = open.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Long, TickAccumulator> e = it.next();
                long tick = e.getKey();
                if (now >= tick + tickMs + graceMs) {
                    ctx.forward(new Record<>(Instant.ofEpochMilli(tick).toString(), e.getValue(), tick));
                    it.remove();
                }
            }
        }
    }

    /** Stage 2: merge partials per tick, emit once the merge window has passed. */
    static final class Merge implements Processor<String, TickAccumulator, String, TickStats> {
        private final long tickMs, graceMs;
        private final Map<Long, TickAccumulator> open = new HashMap<>();
        private final java.util.LinkedHashSet<Long> closed = new java.util.LinkedHashSet<>();
        private ProcessorContext<String, TickStats> ctx;

        Merge(Duration tick, Duration grace) {
            this.tickMs = tick.toMillis();
            this.graceMs = grace.toMillis();
        }

        @Override
        public void init(ProcessorContext<String, TickStats> context) {
            this.ctx = context;
            context.schedule(Duration.ofSeconds(1), PunctuationType.WALL_CLOCK_TIME, this::flush);
        }

        @Override
        public void process(Record<String, TickAccumulator> r) {
            if (r.value() == null) return;
            long tick = Instant.parse(r.key()).toEpochMilli();
            if (closed.contains(tick)) return; // a straggler partial after the tick was published: drop, never double-count
            open.merge(tick, r.value(), TickAccumulator::merge);
        }

        private void flush(long now) {
            Iterator<Map.Entry<Long, TickAccumulator>> it = open.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Long, TickAccumulator> e = it.next();
                long tick = e.getKey();
                // partials arrive at tick + interval + grace; give them another grace period to land
                if (now >= tick + tickMs + 2 * graceMs) {
                    Instant t = Instant.ofEpochMilli(tick);
                    ctx.forward(new Record<>(t.toString(), e.getValue().toStats(t, 10), tick));
                    it.remove();
                    closed.add(tick);
                    while (closed.size() > 100) closed.remove(closed.iterator().next());
                }
            }
        }
    }
}
