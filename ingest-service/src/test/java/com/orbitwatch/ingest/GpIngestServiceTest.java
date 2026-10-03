package com.orbitwatch.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.orbitwatch.contracts.GpElementSet;
import com.orbitwatch.contracts.IngestRun;
import com.orbitwatch.contracts.Topics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class GpIngestServiceTest {

    @TempDir Path tmp;

    private GpIngestService build(KafkaPublisher publisher, ElementChangeDetector detector) throws Exception {
        Path replay = tmp.resolve("gp.json");
        try (var in = getClass().getResourceAsStream("/gp-sample.json")) { Files.copy(in, replay); }
        IngestProperties props = new IngestProperties(
                new IngestProperties.Gp("https://example.invalid/gp.php", List.of("active"), Duration.ofHours(2), replay.toString()),
                new IngestProperties.Satcat(false, "https://example.invalid/satcat.csv", Duration.ofHours(24), null),
                tmp.resolve("cache").toString(), null);
        return new GpIngestService(props, new CelestrakClient(), new SourceCache(tmp.resolve("cache")),
                new SnapshotSeed(props, new SimpleMeterRegistry()), detector, publisher, new SimpleMeterRegistry());
    }

    @Test
    void coldStartPublishesEverythingButNoNewObjectAlerts() throws Exception {
        KafkaPublisher publisher = mock(KafkaPublisher.class);
        when(publisher.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(publisher.sendAll(anyString(), any())).thenReturn(0);
        ElementChangeDetector detector = new ElementChangeDetector();
        GpIngestService svc = build(publisher, detector);
        svc.markWarmed();

        IngestRun run = svc.runOnce();

        assertThat(run.error()).isNull();
        assertThat(run.fetched()).isEqualTo(300);
        assertThat(run.published()).isEqualTo(300);
        assertThat(run.newObjects()).isEqualTo(300);
        assertThat(run.fromCache()).isTrue();
        assertThat(detector.size()).isEqualTo(300);
        verify(publisher, never()).sendAll(eq(Topics.ALERTS), any());
    }

    @Test
    void secondRunWithSameDataPublishesNothing() throws Exception {
        KafkaPublisher publisher = mock(KafkaPublisher.class);
        when(publisher.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(publisher.sendAll(anyString(), any())).thenReturn(0);
        ElementChangeDetector detector = new ElementChangeDetector();
        GpIngestService svc = build(publisher, detector);
        svc.markWarmed();
        svc.runOnce();

        IngestRun second = svc.runOnce();

        assertThat(second.published()).isZero();
        assertThat(second.unchanged()).isEqualTo(300);
        assertThat(second.newObjects()).isZero();
    }

    @Test
    void warmedDetectorEmitsNewObjectAlertsForUnknownIds() throws Exception {
        KafkaPublisher publisher = mock(KafkaPublisher.class);
        when(publisher.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(publisher.sendAll(anyString(), any())).thenReturn(0);
        ElementChangeDetector detector = new ElementChangeDetector();
        // pretend Kafka already knew about 299 of the 300 objects (all but NORAD 900)
        List<GpElementSet> known;
        try (var in = getClass().getResourceAsStream("/gp-sample.json")) {
            known = GpJsonParser.parse(in, "x", java.time.Instant.EPOCH);
        }
        known.stream().filter(e -> e.noradId() != 900).forEach(detector::remember);
        GpIngestService svc = build(publisher, detector);
        svc.markWarmed();

        IngestRun run = svc.runOnce();

        assertThat(run.newObjects()).isEqualTo(1);
        assertThat(run.published()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KafkaPublisher.KeyedRecord>> captor = ArgumentCaptor.forClass(List.class);
        verify(publisher).sendAll(eq(Topics.ALERTS), captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).key()).isEqualTo("new:900");
    }

    /**
     * Regression for the 2026-09-10 Docker run: the live fetch succeeded, the cache volume was not
     * writable, and the whole run failed (nothing published, catalogue empty, container "healthy").
     * A successful fetch must publish even when the cache cannot be written, and the provider's
     * minimum interval must still be honoured from the in-memory copy.
     */
    /**
     * Regression for the 2026-09-10 run on the operator's network: CelesTrak was unreachable
     * (plain HTTP to the host failed too), the cache was cold, the run failed and the catalogue
     * stayed empty. With a committed snapshot present the run must succeed, publish, and be flagged
     * as degraded rather than silently pretending to be live.
     */
    @Test
    void seedsFromCommittedSnapshotWhenProviderIsUnreachableAndCacheIsCold() throws Exception {
        KafkaPublisher publisher = mock(KafkaPublisher.class);
        when(publisher.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(publisher.sendAll(anyString(), any())).thenReturn(0);
        CelestrakClient client = mock(CelestrakClient.class);
        when(client.fetch(anyString())).thenThrow(new CelestrakClient.FetchException("connect timed out"));

        Path seedDir = Files.createDirectory(tmp.resolve("snapshots"));
        try (var in = getClass().getResourceAsStream("/gp-sample.json")) {
            Files.copy(in, seedDir.resolve("celestrak-active-2026-09-09.json"));
        }
        IngestProperties props = new IngestProperties(
                new IngestProperties.Gp("https://celestrak.invalid/gp.php", List.of("active"), Duration.ofHours(2), null),
                new IngestProperties.Satcat(false, "https://celestrak.invalid/satcat.csv", Duration.ofHours(24), null),
                tmp.resolve("cache").toString(), seedDir.toString());
        SnapshotSeed seed = new SnapshotSeed(props, new SimpleMeterRegistry());
        GpIngestService svc = new GpIngestService(props, client, new SourceCache(tmp.resolve("cache")), seed,
                new ElementChangeDetector(), publisher, new SimpleMeterRegistry());

        IngestRun run = svc.runOnce();

        assertThat(run.error()).isNull();
        assertThat(run.published()).isEqualTo(300);
        assertThat(seed.lastFallback()).contains("celestrak-active-2026-09-09.json").contains("connect timed out");
    }

    /** Without a snapshot to fall back on, an unreachable provider must still fail the run loudly. */
    @Test
    void failsTheRunWhenProviderIsUnreachableAndThereIsNoSnapshot() throws Exception {
        KafkaPublisher publisher = mock(KafkaPublisher.class);
        when(publisher.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));
        CelestrakClient client = mock(CelestrakClient.class);
        when(client.fetch(anyString())).thenThrow(new CelestrakClient.FetchException("connect timed out"));
        IngestProperties props = new IngestProperties(
                new IngestProperties.Gp("https://celestrak.invalid/gp.php", List.of("active"), Duration.ofHours(2), null),
                new IngestProperties.Satcat(false, "https://celestrak.invalid/satcat.csv", Duration.ofHours(24), null),
                tmp.resolve("cache").toString(), tmp.resolve("no-such-dir").toString());
        SnapshotSeed seed = new SnapshotSeed(props, new SimpleMeterRegistry());
        GpIngestService svc = new GpIngestService(props, client, new SourceCache(tmp.resolve("cache")), seed,
                new ElementChangeDetector(), publisher, new SimpleMeterRegistry());

        IngestRun run = svc.runOnce();

        assertThat(run.error()).contains("connect timed out");
        assertThat(run.published()).isZero();
        assertThat(seed.lastFallback()).isNull();
    }

    @Test
    void liveFetchStillPublishesWhenCacheCannotBeWrittenAndDoesNotRefetchInsideInterval() throws Exception {
        KafkaPublisher publisher = mock(KafkaPublisher.class);
        when(publisher.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(publisher.sendAll(anyString(), any())).thenReturn(0);
        CelestrakClient client = mock(CelestrakClient.class);
        when(client.fetch(anyString())).thenAnswer(inv -> getClass().getResourceAsStream("/gp-sample.json"));

        Path notADir = Files.writeString(tmp.resolve("cache"), "blocks the cache directory");
        SourceCache cache = new SourceCache(notADir.resolve("cache"));
        IngestProperties props = new IngestProperties(
                new IngestProperties.Gp("https://celestrak.invalid/gp.php", List.of("active"), Duration.ofHours(2), null),
                new IngestProperties.Satcat(false, "https://celestrak.invalid/satcat.csv", Duration.ofHours(24), null),
                notADir.resolve("cache").toString(), null);
        GpIngestService svc = new GpIngestService(props, client, cache, new SnapshotSeed(props, new SimpleMeterRegistry()),
                new ElementChangeDetector(), publisher, new SimpleMeterRegistry());
        svc.markWarmed();

        IngestRun first = svc.runOnce();
        IngestRun second = svc.runOnce();

        assertThat(first.error()).isNull();
        assertThat(first.fromCache()).isFalse();
        assertThat(first.published()).isEqualTo(300);
        assertThat(second.error()).isNull();
        assertThat(second.fromCache()).as("second run inside min interval served from memory").isTrue();
        assertThat(second.unchanged()).isEqualTo(300);
        verify(client, times(1)).fetch(anyString());
        assertThat(cache.persistent()).isFalse();
        assertThat(svc.lastRun()).isSameAs(second);
    }
}
