package com.orbitwatch.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The cache must never fail an ingest run. Unwritable storage is simulated structurally (a regular
 * file where the directory should be, or a non-empty directory where the temp file goes) because
 * permission bits do not stop root, and CI / this sandbox may run tests as root.
 */
class SourceCacheTest {

    @TempDir Path tmp;

    static ByteArrayInputStream payload(String s) { return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8)); }

    static String read(SourceCache.Entry e) throws Exception {
        try (var in = e.open()) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
    }

    @Test
    void writableDirectoryPersistsToDisk() throws Exception {
        SourceCache cache = new SourceCache(tmp.resolve("cache"));
        SourceCache.Entry e = cache.put("gp-active.json", payload("[1,2,3]"));

        assertThat(e.persisted()).isTrue();
        assertThat(Files.readString(tmp.resolve("cache/gp-active.json"))).isEqualTo("[1,2,3]");
        assertThat(Files.exists(tmp.resolve("cache/gp-active.json.tmp"))).isFalse();
        assertThat(cache.persistent()).isTrue();
        assertThat(cache.writeFailures()).isZero();
        // a new instance (restart) sees the persisted payload
        assertThat(read(new SourceCache(tmp.resolve("cache")).get("gp-active.json").orElseThrow())).isEqualTo("[1,2,3]");
    }

    @Test
    void uncreatableDirectoryFallsBackToMemoryInsteadOfThrowing() throws Exception {
        Path notADir = Files.writeString(tmp.resolve("cache"), "i am a file");
        SourceCache cache = new SourceCache(notADir.resolve("sub"));   // construction must not throw

        SourceCache.Entry e = cache.put("gp-active.json", payload("[42]"));

        assertThat(e.persisted()).isFalse();
        assertThat(read(e)).isEqualTo("[42]");
        assertThat(read(cache.get("gp-active.json").orElseThrow())).isEqualTo("[42]");
        assertThat(cache.get("gp-active.json").orElseThrow().isFresh(Duration.ofHours(2))).isTrue();
        assertThat(cache.persistent()).isFalse();
        assertThat(cache.writeFailures()).isGreaterThanOrEqualTo(1);
        assertThat(cache.lastWriteError()).isNotBlank();
    }

    @Test
    void failedWriteKeepsPayloadInMemoryAndRecoversWhenDiskComesBack() throws Exception {
        Path dir = tmp.resolve("cache");
        SourceCache cache = new SourceCache(dir);
        // block the temp file: a non-empty directory at the temp path cannot be overwritten, even by root
        Path blocker = Files.createDirectories(dir.resolve("satcat.csv.tmp"));
        Files.writeString(blocker.resolve("x"), "x");

        SourceCache.Entry e = cache.put("satcat.csv", payload("a,b\n1,2\n"));

        assertThat(e.persisted()).isFalse();
        assertThat(read(cache.get("satcat.csv").orElseThrow())).isEqualTo("a,b\n1,2\n");
        assertThat(cache.writeFailures()).isEqualTo(1);
        assertThat(cache.persistent()).isFalse();

        // storage fixed: the next write persists and the memory copy is dropped
        Files.delete(blocker.resolve("x"));
        Files.delete(blocker);
        SourceCache.Entry again = cache.put("satcat.csv", payload("a,b\n3,4\n"));
        assertThat(again.persisted()).isTrue();
        assertThat(cache.persistent()).isTrue();
        assertThat(read(cache.get("satcat.csv").orElseThrow())).isEqualTo("a,b\n3,4\n");
    }

    @Test
    void missingKeyIsEmpty() {
        assertThat(new SourceCache(tmp.resolve("c")).get("nope")).isEmpty();
    }
}
