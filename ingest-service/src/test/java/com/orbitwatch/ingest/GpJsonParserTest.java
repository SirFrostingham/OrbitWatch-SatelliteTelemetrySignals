package com.orbitwatch.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.orbitwatch.contracts.GpElementSet;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class GpJsonParserTest {

    @Test
    void parsesCelestrakOmmJson() throws Exception {
        Instant now = Instant.parse("2026-09-10T00:00:00Z");
        List<GpElementSet> list;
        try (var in = getClass().getResourceAsStream("/gp-sample.json")) {
            list = GpJsonParser.parse(in, "celestrak:active", now);
        }
        assertThat(list).hasSize(300);
        GpElementSet first = list.get(0);
        assertThat(first.noradId()).isEqualTo(900);
        assertThat(first.objectName()).isEqualTo("CALSPHERE 1");
        assertThat(first.objectId()).isEqualTo("1964-063C");
        assertThat(first.epoch()).isEqualTo(Instant.parse("2026-09-09T20:27:22.665024Z"));
        assertThat(first.meanMotion()).isEqualTo(13.76699774);
        assertThat(first.bstar()).isEqualTo(0.00044767103);
        assertThat(first.fingerprint()).hasSize(16);
        assertThat(first.ingestedAt()).isEqualTo(now);
    }

    @Test
    void fingerprintIgnoresNonOrbitalFields() {
        GpElementSet a = new GpElementSet(1, "A", "X", Instant.parse("2026-01-01T00:00:00Z"), 15.5, 0.001, 51.6, 1, 2, 3,
                1e-4, 1e-5, 0, 999, 10, "U", 0, "s1", Instant.parse("2026-01-01T00:00:00Z"), null);
        GpElementSet b = new GpElementSet(1, "A RENAMED", "X", Instant.parse("2026-01-01T00:00:00Z"), 15.5, 0.001, 51.6, 1, 2, 3,
                1e-4, 1e-5, 0, 999, 10, "U", 0, "s2", Instant.parse("2026-02-01T00:00:00Z"), null);
        assertThat(GpJsonParser.withFingerprint(a).fingerprint()).isEqualTo(GpJsonParser.withFingerprint(b).fingerprint());
    }

    @Test
    void fingerprintChangesWithEpoch() {
        GpElementSet a = new GpElementSet(1, "A", "X", Instant.parse("2026-01-01T00:00:00Z"), 15.5, 0.001, 51.6, 1, 2, 3,
                1e-4, 1e-5, 0, 999, 10, "U", 0, "s", Instant.EPOCH, null);
        GpElementSet b = new GpElementSet(1, "A", "X", Instant.parse("2026-01-02T00:00:00Z"), 15.5, 0.001, 51.6, 1, 2, 3,
                1e-4, 1e-5, 0, 999, 10, "U", 0, "s", Instant.EPOCH, null);
        assertThat(GpJsonParser.withFingerprint(a).fingerprint()).isNotEqualTo(GpJsonParser.withFingerprint(b).fingerprint());
    }
}
