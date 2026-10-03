package com.orbitwatch.ingest;

import com.orbitwatch.contracts.IngestRun;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Readiness reflects whether ingest is actually producing: DOWN when the most recent GP or SATCAT
 * run failed, UP otherwise (including before the first run). It is part of the readiness group
 * (application.yml), so the Docker HEALTHCHECK and `docker compose ps` show a failing ingest as
 * unhealthy instead of the container looking fine while the catalogue stays empty.
 *
 * <p>Liveness is deliberately unaffected: a failed run is a data problem, not a reason to restart.
 * A cache that has fallen back to memory is reported as a detail, not DOWN — the pipeline works.
 * Same for a run seeded from the committed snapshot: it is degraded (`degraded` detail, and the
 * `orbitwatch_ingest_seed_fallbacks` counter alarms), but the system is producing, so not DOWN.
 */
@Component("ingest")
public class IngestHealthIndicator implements HealthIndicator {
    private final GpIngestService gp;
    private final SatcatIngestService satcat;
    private final SourceCache cache;
    private final SnapshotSeed seed;

    public IngestHealthIndicator(GpIngestService gp, SatcatIngestService satcat, SourceCache cache, SnapshotSeed seed) {
        this.gp = gp;
        this.satcat = satcat;
        this.cache = cache;
        this.seed = seed;
    }

    @Override
    public Health health() {
        IngestRun g = gp.lastRun();
        IngestRun s = satcat.lastRun();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("lastGpRun", summary(g));
        details.put("lastSatcatRun", summary(s));
        details.put("cache", cache.persistent() ? "disk:" + cache.dir() : "memory-only (not persisted across restarts)");
        details.put("cacheWriteFailures", cache.writeFailures());
        if (cache.lastWriteError() != null) details.put("lastCacheWriteError", cache.lastWriteError());
        if (seed.lastFallback() != null) details.put("degraded", "running on committed snapshot: " + seed.lastFallback());
        boolean failed = (g != null && g.error() != null) || (s != null && s.error() != null);
        return (failed ? Health.down() : Health.up()).withDetails(details).build();
    }

    private static Object summary(IngestRun r) {
        if (r == null) return "none yet";
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("finishedAt", String.valueOf(r.finishedAt()));
        m.put("fetched", r.fetched());
        m.put("published", r.published());
        m.put("fromCache", r.fromCache());
        if (r.error() != null) m.put("error", r.error());
        return m;
    }
}
