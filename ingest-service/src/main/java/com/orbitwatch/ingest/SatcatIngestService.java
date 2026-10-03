package com.orbitwatch.ingest;

import com.orbitwatch.contracts.CatalogObject;
import com.orbitwatch.contracts.IngestRun;
import com.orbitwatch.contracts.Topics;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Publishes SATCAT rows (owner / object type / launch data) for every object that has GP elements.
 * The whole SATCAT is ~70k rows including decayed objects; we only publish the ~16k that are on
 * orbit and in the GP feed, keyed by NORAD id to the compacted catalogue topic. Rows are only
 * re-published when their content changes.
 */
@Service
public class SatcatIngestService {
    private static final Logger log = LoggerFactory.getLogger(SatcatIngestService.class);

    private final IngestProperties props;
    private final CelestrakClient client;
    private final SourceCache cache;
    private final SnapshotSeed seed;
    private final ElementChangeDetector detector;
    private final KafkaPublisher publisher;
    private final Map<Integer, Integer> lastHash = new ConcurrentHashMap<>();
    private volatile IngestRun lastRun;

    public SatcatIngestService(IngestProperties props, CelestrakClient client, SourceCache cache, SnapshotSeed seed,
                               ElementChangeDetector detector, KafkaPublisher publisher) {
        this.props = props;
        this.client = client;
        this.cache = cache;
        this.seed = seed;
        this.detector = detector;
        this.publisher = publisher;
    }

    public void remember(CatalogObject c) { lastHash.put(c.noradId(), contentHash(c)); }

    public IngestRun runOnce() {
        Instant started = Instant.now();
        String runId = UUID.randomUUID().toString();
        IngestRun result;
        try {
            result = doRun(runId, started);
        } catch (Exception e) {
            log.error("satcat ingest run {} failed", runId, e);
            result = new IngestRun(runId, "celestrak:satcat", started, Instant.now(), false, 0, 0, 0, 0, e.toString());
        }
        publisher.send(Topics.INGEST_RUNS, runId, result);
        lastRun = result;
        return result;
    }

    /** Most recent SATCAT run, or null before the first one. */
    public IngestRun lastRun() { return lastRun; }

    private IngestRun doRun(String runId, Instant started) throws IOException {
        Instant now = Instant.now();
        boolean fromCache;
        List<CatalogObject> rows;
        String key = "satcat.csv";
        if (props.satcat().replayFile() != null && !props.satcat().replayFile().isBlank()) {
            try (InputStream in = Files.newInputStream(Path.of(props.satcat().replayFile()))) { rows = SatcatCsvParser.parse(in, now); }
            fromCache = true;
        } else {
            var cached = cache.get(key);
            if (cached.isPresent() && cached.get().isFresh(props.satcat().minInterval())) {
                try (InputStream in = cached.get().open()) { rows = SatcatCsvParser.parse(in, now); }
                fromCache = true;
            } else {
                try (InputStream in = client.fetch(props.satcat().url())) {
                    cache.put(key, in);
                    fromCache = false;
                } catch (CelestrakClient.FetchException ex) {
                    if (cached.isEmpty()) {
                        // No live data and no cache: seed from the committed snapshot (flagged) rather
                        // than fail — owner/type metadata missing degrades the dashboard needlessly.
                        var file = seed.satcat();
                        if (file.isEmpty()) throw ex;
                        seed.used(file.get(), ex.getMessage());
                        try (InputStream in = Files.newInputStream(file.get())) { rows = SatcatCsvParser.parse(in, now); }
                        return publish(runId, started, rows, true);
                    }
                    log.warn("satcat fetch failed ({}); using stale cache", ex.getMessage());
                    fromCache = true;
                }
                try (InputStream in = cache.get(key).orElseThrow().open()) { rows = SatcatCsvParser.parse(in, now); }
            }
        }

        return publish(runId, started, rows, fromCache);
    }

    private IngestRun publish(String runId, Instant started, List<CatalogObject> rows, boolean fromCache) throws IOException {
        List<KafkaPublisher.KeyedRecord> toPublish = rows.stream()
                .filter(c -> detector.isKnown(c.noradId()))
                .filter(c -> !Integer.valueOf(contentHash(c)).equals(lastHash.get(c.noradId())))
                .map(c -> new KafkaPublisher.KeyedRecord(String.valueOf(c.noradId()), c))
                .toList();
        int failures = publisher.sendAll(Topics.CATALOG_OBJECTS, toPublish);
        if (failures > 0) throw new IOException(failures + " catalog records failed to publish");
        toPublish.forEach(r -> remember((CatalogObject) r.value()));
        int unchanged = rows.size() - toPublish.size();
        log.info("satcat ingest run {}: rows={} published={} unchanged/filtered={} fromCache={}", runId, rows.size(), toPublish.size(), unchanged, fromCache);
        return new IngestRun(runId, "celestrak:satcat", started, Instant.now(), fromCache, rows.size(), toPublish.size(), unchanged, 0, null);
    }

    /** Content hash excluding ingestedAt so an identical row is not republished. */
    static int contentHash(CatalogObject c) {
        return java.util.Objects.hash(c.noradId(), c.objectName(), c.objectId(), c.objectType(), c.opsStatusCode(),
                c.owner(), c.launchDate(), c.launchSite(), c.decayDate(), c.rcs());
    }
}
