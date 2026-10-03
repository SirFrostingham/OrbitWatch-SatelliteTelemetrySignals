package com.orbitwatch.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.orbitwatch.contracts.IngestRun;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

class IngestHealthIndicatorTest {

    @TempDir Path tmp;

    static IngestRun run(String error) {
        return new IngestRun("r", "celestrak:active", Instant.EPOCH, Instant.EPOCH, false, 10, 10, 0, 0, error);
    }

    IngestHealthIndicator indicator(IngestRun gp, IngestRun satcat, SourceCache cache) {
        return indicator(gp, satcat, cache, seed(null));
    }

    IngestHealthIndicator indicator(IngestRun gp, IngestRun satcat, SourceCache cache, SnapshotSeed seed) {
        GpIngestService g = mock(GpIngestService.class);
        SatcatIngestService s = mock(SatcatIngestService.class);
        when(g.lastRun()).thenReturn(gp);
        when(s.lastRun()).thenReturn(satcat);
        return new IngestHealthIndicator(g, s, cache, seed);
    }

    SnapshotSeed seed(String seedDir) {
        IngestProperties props = new IngestProperties(
                new IngestProperties.Gp("https://celestrak.invalid/gp.php", List.of("active"), Duration.ofHours(2), null),
                new IngestProperties.Satcat(false, "https://celestrak.invalid/satcat.csv", Duration.ofHours(24), null),
                tmp.resolve("c").toString(), seedDir);
        return new SnapshotSeed(props, new SimpleMeterRegistry());
    }

    @Test
    void upBeforeFirstRun() {
        Health h = indicator(null, null, new SourceCache(tmp.resolve("c"))).health();
        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails()).containsEntry("lastGpRun", "none yet");
    }

    @Test
    void downWhenLastGpRunFailed() {
        Health h = indicator(run("java.nio.file.AccessDeniedException: /data/cache/gp-active.json.tmp"), run(null),
                new SourceCache(tmp.resolve("c"))).health();
        assertThat(h.getStatus()).isEqualTo(Status.DOWN);
        assertThat(h.getDetails().get("lastGpRun").toString()).contains("AccessDeniedException");
    }

    @Test
    void downWhenLastSatcatRunFailed() {
        assertThat(indicator(run(null), run("boom"), new SourceCache(tmp.resolve("c"))).health().getStatus()).isEqualTo(Status.DOWN);
    }

    /** Running on the committed snapshot is degraded, not down — the pipeline is producing. */
    @Test
    void snapshotSeededRunIsReportedAsDegradedButStillUp() {
        SnapshotSeed seed = seed(null);
        seed.used(Path.of("/data/snapshots/celestrak-active-2026-09-09.json"), "connect timed out");
        Health h = indicator(run(null), run(null), new SourceCache(tmp.resolve("c")), seed).health();
        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails().get("degraded").toString())
                .contains("committed snapshot").contains("celestrak-active-2026-09-09.json");
    }

    @Test
    void memoryOnlyCacheIsReportedButStillUp() throws Exception {
        SourceCache cache = new SourceCache(Files.writeString(tmp.resolve("f"), "x").resolve("c"));
        Health h = indicator(run(null), run(null), cache).health();
        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails().get("cache").toString()).startsWith("memory-only");
        assertThat((Long) h.getDetails().get("cacheWriteFailures")).isGreaterThanOrEqualTo(1L);
    }
}
