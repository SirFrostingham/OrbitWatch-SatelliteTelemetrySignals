package com.orbitwatch.ingest;

import com.orbitwatch.contracts.AlertEvent;
import com.orbitwatch.contracts.GpElementSet;
import com.orbitwatch.contracts.IngestRun;
import com.orbitwatch.contracts.Topics;
import com.orbitwatch.ingest.ElementChangeDetector.Outcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * One ingest cycle for GP element sets:
 * <ol>
 *   <li>Fetch (network, or cache if fresh, or replay file) — never inside the provider's minimum interval.
 *       If the provider is unreachable and there is no cache, seed from the committed snapshot
 *       ({@link SnapshotSeed}) instead of failing the run.</li>
 *   <li>Parse + fingerprint.</li>
 *   <li>Diff against last published fingerprints; publish only NEW/CHANGED to the compacted topic.</li>
 *   <li>Emit NEW_OBJECT alerts (only once the detector has been warmed, so a cold start is not 16k alerts).</li>
 *   <li>Emit an {@link IngestRun} summary for observability.</li>
 * </ol>
 */
@Service
public class GpIngestService {
    private static final Logger log = LoggerFactory.getLogger(GpIngestService.class);

    private final IngestProperties props;
    private final CelestrakClient client;
    private final SourceCache cache;
    private final SnapshotSeed seed;
    private final ElementChangeDetector detector;
    private final KafkaPublisher publisher;
    private final Counter published, unchanged, fetchFailures;
    private final Timer runTimer;
    private final List<IngestRun> recentRuns = new CopyOnWriteArrayList<>();
    private volatile boolean warmed;

    public GpIngestService(IngestProperties props, CelestrakClient client, SourceCache cache, SnapshotSeed seed,
                           ElementChangeDetector detector, KafkaPublisher publisher, MeterRegistry meters) {
        this.props = props;
        this.client = client;
        this.cache = cache;
        this.seed = seed;
        this.detector = detector;
        this.publisher = publisher;
        this.published = meters.counter("orbitwatch.ingest.gp.published");
        this.unchanged = meters.counter("orbitwatch.ingest.gp.unchanged");
        this.fetchFailures = meters.counter("orbitwatch.ingest.gp.fetch_failures");
        this.runTimer = meters.timer("orbitwatch.ingest.gp.run");
        meters.gauge("orbitwatch.ingest.gp.known_objects", detector, ElementChangeDetector::size);
    }

    public void markWarmed() { this.warmed = true; }

    public List<IngestRun> recentRuns() { return List.copyOf(recentRuns); }

    /** Most recent GP run, or null before the first one. */
    public IngestRun lastRun() { return recentRuns.isEmpty() ? null : recentRuns.get(0); }

    public IngestRun runOnce() {
        Instant started = Instant.now();
        String runId = UUID.randomUUID().toString();
        IngestRun result;
        try {
            result = runTimer.recordCallable(() -> doRun(runId, started));
        } catch (Exception e) {
            log.error("gp ingest run {} failed", runId, e);
            fetchFailures.increment();
            result = new IngestRun(runId, gpSource(), started, Instant.now(), false, 0, 0, 0, 0, e.toString());
        }
        publisher.send(Topics.INGEST_RUNS, runId, result);
        recentRuns.add(0, result);
        while (recentRuns.size() > 50) recentRuns.remove(recentRuns.size() - 1);
        return result;
    }

    private String gpSource() {
        return "celestrak:" + String.join(",", props.gp().groups());
    }

    private IngestRun doRun(String runId, Instant started) throws IOException {
        Instant now = Instant.now();
        List<GpElementSet> all = new ArrayList<>();
        boolean anyFromCache = false;
        for (String group : props.gp().groups()) {
            Fetched f = fetchGroup(group);
            anyFromCache |= f.fromCache;
            try (InputStream in = f.in) {
                all.addAll(GpJsonParser.parse(in, "celestrak:" + group, now));
            }
        }

        List<KafkaPublisher.KeyedRecord> toPublish = new ArrayList<>();
        List<KafkaPublisher.KeyedRecord> alerts = new ArrayList<>();
        int unchangedCount = 0, newCount = 0;
        boolean emitNewAlerts = warmed && detector.size() > 0;
        for (GpElementSet e : all) {
            Outcome o = detector.classify(e);
            if (o == Outcome.UNCHANGED) { unchangedCount++; continue; }
            if (o == Outcome.NEW) {
                newCount++;
                if (emitNewAlerts) alerts.add(new KafkaPublisher.KeyedRecord(alertId(e), newObjectAlert(e, now)));
            }
            toPublish.add(new KafkaPublisher.KeyedRecord(String.valueOf(e.noradId()), e));
        }

        int failures = publisher.sendAll(Topics.GP_ELEMENTS, toPublish);
        if (failures > 0) throw new IOException(failures + " records failed to publish");
        toPublish.forEach(r -> detector.remember((GpElementSet) r.value()));
        if (!alerts.isEmpty()) publisher.sendAll(Topics.ALERTS, alerts);

        published.increment(toPublish.size());
        unchanged.increment(unchangedCount);
        log.info("gp ingest run {}: fetched={} published={} unchanged={} new={} fromCache={}",
                runId, all.size(), toPublish.size(), unchangedCount, newCount, anyFromCache);
        return new IngestRun(runId, gpSource(), started, Instant.now(), anyFromCache, all.size(),
                toPublish.size(), unchangedCount, newCount, null);
    }

    private record Fetched(InputStream in, boolean fromCache) {}

    private Fetched fetchGroup(String group) throws IOException {
        String key = "gp-" + group + ".json";
        if (props.gp().replayFile() != null && !props.gp().replayFile().isBlank()) {
            Path p = Path.of(props.gp().replayFile());
            log.info("replay mode: reading GP data from {}", p);
            return new Fetched(Files.newInputStream(p), true);
        }
        var cached = cache.get(key);
        if (cached.isPresent() && cached.get().isFresh(props.gp().minInterval())) {
            log.info("using cached {} (fetched {}), inside provider min interval {}", key, cached.get().fetchedAt(), props.gp().minInterval());
            return new Fetched(cached.get().open(), true);
        }
        String url = props.gp().baseUrl() + "?GROUP=" + group + "&FORMAT=json";
        try (InputStream in = client.fetch(url)) {
            SourceCache.Entry e = cache.put(key, in);
            return new Fetched(e.open(), false);
        } catch (CelestrakClient.FetchException ex) {
            fetchFailures.increment();
            if (cached.isPresent()) {
                log.warn("fetch failed ({}); serving stale cache from {}", ex.getMessage(), cached.get().fetchedAt());
                return new Fetched(cached.get().open(), true);
            }
            // No live data and no cache. Rather than fail the run (and leave every downstream service
            // idling on an empty catalogue), fall back to the committed snapshot and flag it.
            var file = seed.gp(group);
            if (file.isPresent()) {
                seed.used(file.get(), ex.getMessage());
                return new Fetched(Files.newInputStream(file.get()), true);
            }
            throw ex;
        }
    }

    private static String alertId(GpElementSet e) {
        return "new:" + e.noradId();
    }

    private static AlertEvent newObjectAlert(GpElementSet e, Instant now) {
        return new AlertEvent(alertId(e), AlertEvent.AlertType.NEW_OBJECT, AlertEvent.Severity.INFO, now,
                e.noradId(), e.objectName(), null, null,
                "New object in catalogue: " + e.objectName() + " (" + e.objectId() + ")",
                Map.of("objectId", String.valueOf(e.objectId()), "epoch", e.epoch().toString(),
                        "meanMotion", e.meanMotion(), "inclination", e.inclination()),
                alertId(e));
    }
}
