package com.orbitwatch.analytics;

import com.orbitwatch.astro.ConjunctionScreener;
import com.orbitwatch.contracts.AlertEvent;
import com.orbitwatch.contracts.GpElementSet;
import com.orbitwatch.contracts.StateVectorEvent;
import com.orbitwatch.contracts.TickStats;
import com.orbitwatch.contracts.Topics;
import com.orbitwatch.contracts.kafka.JsonSerde;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Named;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.Repartitioned;
import org.apache.kafka.streams.kstream.Suppressed;
import org.apache.kafka.streams.kstream.TimeWindows;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.processor.TimestampExtractor;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.StoreBuilder;
import org.apache.kafka.streams.state.Stores;
import org.apache.kafka.streams.state.WindowStore;

/**
 * The analytics topology. Pure function of a StreamsBuilder so it can be exercised with
 * TopologyTestDriver without Spring or a broker.
 *
 * <pre>
 *  state-vectors ──┬─► per-partition partial TickAccumulator ─► repartition(1) ─► merge ─► tick-stats
 *                  ├─► flatMap ghost cells ─► window(tick) per cell ─► CellBucket ─► suppress ─► pair screen ─┐
 *                  └─► filter stale ─────────────────────────────────────────────────────────────────────────┤
 *  gp-elements ────┬─► DecayWatch ────────────────────────────────────────────────────────────────────────────┤
 *                  └─► ManeuverProcessor (store: last element set per object) ─────────────────────────────────┤
 *                                                                                    AlertDeduper (store) ◄────┘
 *                                                                                              │
 *                                                                                            alerts
 * </pre>
 */
public final class AnalyticsTopology {
    private AnalyticsTopology() {}

    static final String CELL_STORE = "cell-bucket-store";
    static final String LAST_ELEMENTS_STORE = "last-elements-store";

    /** Event time = the propagation tick, so windows align to ticks regardless of publish latency. */
    public static final class TickTimestampExtractor implements TimestampExtractor {
        @Override
        public long extract(org.apache.kafka.clients.consumer.ConsumerRecord<Object, Object> record, long partitionTime) {
            if (record.value() instanceof StateVectorEvent v && v.tick() != null) return v.tick().toEpochMilli();
            return record.timestamp();
        }
    }

    public static void build(StreamsBuilder builder, AnalyticsProperties cfg) {
        Serde<StateVectorEvent> vectorSerde = JsonSerde.of(StateVectorEvent.class);
        Serde<GpElementSet> elementsSerde = JsonSerde.of(GpElementSet.class);
        Serde<AlertEvent> alertSerde = JsonSerde.of(AlertEvent.class);
        Serde<TickAccumulator> accSerde = JsonSerde.of(TickAccumulator.class);
        Serde<CellBucket> bucketSerde = JsonSerde.of(CellBucket.class);
        Serde<CellBucket.Entry> entrySerde = JsonSerde.of(CellBucket.Entry.class);
        Serde<TickStats> statsSerde = JsonSerde.of(TickStats.class);

        Duration tick = cfg.tickInterval();
        Duration grace = cfg.windowGrace();
        Duration retention = tick.plus(grace).multipliedBy(6);
        TimeWindows tickWindows = TimeWindows.ofSizeAndGrace(tick, grace);
        double staleHours = cfg.staleElementsAfter().toHours();

        // ---- state stores for the custom processors ----
        StoreBuilder<KeyValueStore<String, Long>> dedupeStore =
                Stores.keyValueStoreBuilder(Stores.persistentKeyValueStore(AlertDeduper.STORE), Serdes.String(), Serdes.Long());
        StoreBuilder<KeyValueStore<String, GpElementSet>> lastElementsStore =
                Stores.keyValueStoreBuilder(Stores.persistentKeyValueStore(LAST_ELEMENTS_STORE), Serdes.String(), elementsSerde);
        builder.addStateStore(dedupeStore);
        builder.addStateStore(lastElementsStore);

        // ---- inputs ----
        KStream<String, StateVectorEvent> vectors = builder.stream(Topics.STATE_VECTORS,
                Consumed.with(Serdes.String(), vectorSerde).withTimestampExtractor(new TickTimestampExtractor()));
        KStream<String, GpElementSet> elements = builder.stream(Topics.GP_ELEMENTS,
                Consumed.with(Serdes.String(), elementsSerde));

        // ---- 1. per-tick statistics: two-stage (partial per partition -> merge) ----
        vectors.process(() -> new TickStatsProcessors.Partial(tick, grace, staleHours, cfg.decay().warningPerigeeKm()), Named.as("tick-partial"))
                .repartition(Repartitioned.<String, TickAccumulator>as("tick-partials").withKeySerde(Serdes.String()).withValueSerde(accSerde).withNumberOfPartitions(1))
                .process(() -> new TickStatsProcessors.Merge(tick, grace), Named.as("tick-merge"))
                .to(Topics.TICK_STATS, Produced.with(Serdes.String(), statsSerde));

        // ---- 2. conjunction screening via spatial-cell partitioning ----
        ConjunctionScreener screener = new ConjunctionScreener(cfg.conjunction().cellKm(), cfg.conjunction().thresholdKm());
        double cellKm = cfg.conjunction().cellKm();
        KStream<String, AlertEvent> conjunctionAlerts = vectors
                .filter((k, v) -> v != null)
                .flatMap((k, v) -> {
                    long own = ConjunctionScreener.cellKey(v.x(), v.y(), v.z(), cellKm);
                    List<KeyValue<String, CellBucket.Entry>> out = new ArrayList<>(4);
                    for (long cell : screener.ghostCells(v.x(), v.y(), v.z())) {
                        out.add(KeyValue.pair(Long.toString(cell),
                                new CellBucket.Entry(v.noradId(), v.objectName(), v.objectType(), v.regime(), v.x(), v.y(), v.z(), v.altKm(), cell == own)));
                    }
                    return out;
                }, Named.as("to-ghost-cells"))
                .groupByKey(Grouped.with("by-cell", Serdes.String(), entrySerde))
                .windowedBy(tickWindows)
                .aggregate(CellBucket::new, (k, e, b) -> b.add(e),
                        Materialized.<String, CellBucket, WindowStore<org.apache.kafka.common.utils.Bytes, byte[]>>as(CELL_STORE)
                                .withKeySerde(Serdes.String()).withValueSerde(bucketSerde).withRetention(retention))
                .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()).withName("cell-suppress"))
                .toStream()
                .flatMap((wk, bucket) -> screenBucket(wk, bucket, screener, cfg.conjunction()), Named.as("screen-cells"));

        // ---- 3. stale element sets (observed on every tick; deduped downstream) ----
        KStream<String, AlertEvent> staleAlerts = vectors
                .filter((k, v) -> v != null && v.elementAgeHours() > staleHours)
                .map((k, v) -> {
                    String id = "stale:" + v.noradId() + ":" + v.elementEpoch();
                    long days = Math.round(v.elementAgeHours() / 24.0);
                    return KeyValue.pair(id, new AlertEvent(id, AlertEvent.AlertType.STALE_ELEMENTS, AlertEvent.Severity.INFO,
                            v.tick(), v.noradId(), v.objectName(), null, null,
                            "Stale elements: " + v.objectName() + " last updated " + days + " days ago",
                            Map.of("elementEpoch", v.elementEpoch().toString(), "ageDays", days, "regime", nz(v.regime())),
                            "stale:" + v.noradId()));
                }, Named.as("stale-to-alert"));

        // ---- 4. decay watch on new element sets ----
        DecayWatch decay = new DecayWatch(cfg.decay());
        KStream<String, AlertEvent> decayAlerts = elements
                .flatMapValues((k, e) -> e == null ? List.of() : decay.check(e).map(List::of).orElse(List.of()), Named.as("decay-watch"))
                .selectKey((k, a) -> a.alertId(), Named.as("decay-key"));

        // ---- 5. manoeuvre candidates (stateful: last element set per object) ----
        ManeuverDetector maneuver = new ManeuverDetector(cfg.maneuver());
        KStream<String, AlertEvent> maneuverAlerts = elements.process(() -> new Processor<String, GpElementSet, String, AlertEvent>() {
            private KeyValueStore<String, GpElementSet> store;
            private ProcessorContext<String, AlertEvent> ctx;
            @Override public void init(ProcessorContext<String, AlertEvent> context) { ctx = context; store = context.getStateStore(LAST_ELEMENTS_STORE); }
            @Override public void process(Record<String, GpElementSet> r) {
                if (r.value() == null) return;
                GpElementSet prev = store.get(r.key());
                store.put(r.key(), r.value());
                maneuver.compare(prev, r.value()).ifPresent(a -> ctx.forward(r.withKey(a.alertId()).withValue(a)));
            }
        }, Named.as("maneuver-detector"), LAST_ELEMENTS_STORE);

        // ---- merge + dedupe + publish ----
        conjunctionAlerts.merge(staleAlerts).merge(decayAlerts).merge(maneuverAlerts)
                .process(() -> new AlertDeduper(cfg.alertDedupeTtl()), Named.as("alert-deduper"), AlertDeduper.STORE)
                .to(Topics.ALERTS, Produced.with(Serdes.String(), alertSerde));
    }

    /** Screen one closed cell bucket: report a pair exactly once — in the own cell of the lower id. */
    static List<KeyValue<String, AlertEvent>> screenBucket(Windowed<String> wk, CellBucket bucket, ConjunctionScreener screener,
                                                          AnalyticsProperties.Conjunction cfg) {
        if (bucket == null || bucket.entries.size() < 2) return List.of();
        Map<Integer, CellBucket.Entry> byId = new HashMap<>();
        List<ConjunctionScreener.Object3D> objs = new ArrayList<>(bucket.entries.size());
        for (CellBucket.Entry e : bucket.entries) {
            if (byId.putIfAbsent(e.id(), e) == null) objs.add(new ConjunctionScreener.Object3D(e.id(), e.x(), e.y(), e.z()));
        }
        List<KeyValue<String, AlertEvent>> out = new ArrayList<>();
        Instant tick = Instant.ofEpochMilli(wk.window().start());
        for (ConjunctionScreener.CloseApproach ca : screener.screenBucket(objs)) {
            CellBucket.Entry a = byId.get((int) ca.idA()), b = byId.get((int) ca.idB());
            if (!a.resident()) continue; // idA is the lower id; only its own cell reports the pair
            if (ca.distanceKm() < cfg.ignoreBelowKm()) continue; // docked / co-catalogued
            AlertEvent.Severity sev = ca.distanceKm() < cfg.criticalKm() ? AlertEvent.Severity.CRITICAL
                    : ca.distanceKm() < cfg.warningKm() ? AlertEvent.Severity.WARNING : AlertEvent.Severity.INFO;
            String id = "conj:" + a.id() + ":" + b.id() + ":" + tick.toEpochMilli();
            Map<String, Object> details = new HashMap<>();
            details.put("distanceKm", Math.round(ca.distanceKm() * 1000) / 1000.0);
            details.put("altitudeKm", Math.round(a.alt()));
            details.put("regime", nz(a.regime()));
            details.put("primaryType", nz(a.type()));
            details.put("secondaryType", nz(b.type()));
            details.put("cell", wk.key());
            out.add(KeyValue.pair(id, new AlertEvent(id, AlertEvent.AlertType.CONJUNCTION, sev, tick,
                    a.id(), a.name(), b.id(), b.name(),
                    String.format("%s and %s within %.2f km", a.name(), b.name(), ca.distanceKm()),
                    details, "conj:" + a.id() + ":" + b.id())));
        }
        return out;
    }

    private static String nz(String s) { return s == null ? "UNK" : s; }
}
