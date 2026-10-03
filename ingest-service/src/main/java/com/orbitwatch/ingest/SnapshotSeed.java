package com.orbitwatch.ingest;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Last-resort data source: a snapshot of the provider's files committed with the repository and
 * mounted read-only (compose mounts {@code ./data} at {@code /data/snapshots}).
 *
 * <p>Why this exists: on 2026-09-10 CelesTrak was unreachable from the operator's network entirely
 * — plain HTTP to the host failed too, while other hosts answered — and with no warm cache the
 * ingest run failed, so the catalogue stayed empty and every downstream service idled. A demo that
 * only works when a third party is reachable from your network is not a demo. The provider stays
 * the source of truth: the seed is used only when a fetch fails <em>and</em> there is no cache, the
 * run is flagged degraded (counter + health detail + WARN), and the next scheduled run tries the
 * network again.
 *
 * <p>Files are found by convention rather than by name, so refreshing a snapshot is a file drop:
 * {@code celestrak-<group>-*.json} for GP, {@code satcat-*.csv} for SATCAT, newest name wins.
 */
@Component
public class SnapshotSeed {
    private static final Logger log = LoggerFactory.getLogger(SnapshotSeed.class);

    private final Path dir;
    private final Counter fallbacks;
    private volatile String lastFallback;

    public SnapshotSeed(IngestProperties props, MeterRegistry meters) {
        this.dir = props.seedDir() == null || props.seedDir().isBlank() ? null : Path.of(props.seedDir());
        this.fallbacks = meters.counter("orbitwatch.ingest.seed_fallbacks");
        if (dir != null && !Files.isDirectory(dir)) {
            log.info("no snapshot seed directory at {}; a provider outage with a cold cache will fail the run", dir);
        }
    }

    public Optional<Path> gp(String group) { return newest("celestrak-" + group + "-", ".json"); }

    public Optional<Path> satcat() { return newest("satcat-", ".csv"); }

    /** Records that a seed was used, for the health endpoint, the metric and the log. */
    public void used(Path file, String reason) {
        fallbacks.increment();
        lastFallback = Instant.now() + " " + file.getFileName() + " (" + reason + ")";
        log.warn("provider unreachable and no cache; seeding from committed snapshot {} — data is static until the "
                + "next successful fetch. Cause: {}", file, reason);
    }

    public String lastFallback() { return lastFallback; }

    public Path dir() { return dir; }

    private Optional<Path> newest(String prefix, String suffix) {
        if (dir == null || !Files.isDirectory(dir)) return Optional.empty();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith(prefix) && p.getFileName().toString().endsWith(suffix))
                    .max(Comparator.comparing(p -> p.getFileName().toString()));
        } catch (IOException e) {
            log.warn("could not list snapshot seed directory {}: {}", dir, e.toString());
            return Optional.empty();
        } catch (UncheckedIOException e) {
            return Optional.empty();
        }
    }
}
