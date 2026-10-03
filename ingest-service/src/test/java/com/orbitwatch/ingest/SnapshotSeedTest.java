package com.orbitwatch.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SnapshotSeedTest {

    @TempDir Path tmp;

    private SnapshotSeed seed(String dir) {
        IngestProperties props = new IngestProperties(
                new IngestProperties.Gp("https://celestrak.invalid/gp.php", List.of("active"), Duration.ofHours(2), null),
                new IngestProperties.Satcat(false, "https://celestrak.invalid/satcat.csv", Duration.ofHours(24), null),
                tmp.resolve("cache").toString(), dir);
        return new SnapshotSeed(props, new SimpleMeterRegistry());
    }

    @Test
    void findsSnapshotsByConventionAndPrefersTheNewest() throws Exception {
        Files.createFile(tmp.resolve("celestrak-active-2026-09-01.json"));
        Path newer = Files.createFile(tmp.resolve("celestrak-active-2026-09-09.json"));
        Path satcat = Files.createFile(tmp.resolve("satcat-2026-09-09.csv"));
        SnapshotSeed seed = seed(tmp.toString());

        assertThat(seed.gp("active")).contains(newer);
        assertThat(seed.satcat()).contains(satcat);
    }

    @Test
    void returnsEmptyForAGroupWithNoSnapshotSoTheWrongDataIsNeverSubstituted() throws Exception {
        Files.createFile(tmp.resolve("celestrak-active-2026-09-09.json"));
        assertThat(seed(tmp.toString()).gp("stations")).isEmpty();
    }

    @Test
    void missingOrUnconfiguredDirectoryIsNotAnError() {
        assertThat(seed(null).gp("active")).isEmpty();
        assertThat(seed(tmp.resolve("nope").toString()).satcat()).isEmpty();
    }

    @Test
    void usageIsRecordedForHealthAndMetrics() {
        SnapshotSeed seed = seed(tmp.toString());
        assertThat(seed.lastFallback()).isNull();
        seed.used(tmp.resolve("celestrak-active-2026-09-09.json"), "Remote host terminated the handshake");
        assertThat(seed.lastFallback()).contains("celestrak-active-2026-09-09.json").contains("handshake");
    }
}
