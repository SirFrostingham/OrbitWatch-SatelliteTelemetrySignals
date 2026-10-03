package com.orbitwatch.propagation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.orbitwatch.contracts.CatalogObject;
import com.orbitwatch.contracts.GpElementSet;
import com.orbitwatch.contracts.StateVectorEvent;
import com.orbitwatch.contracts.Topics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

class PropagationTickerTest {

    // ISS element set from the CelesTrak snapshot used for reference vectors
    static GpElementSet iss() {
        return new GpElementSet(25544, "ISS (ZARYA)", "1998-067A", Instant.parse("2026-09-09T18:00:00Z"),
                15.49, 0.0004, 51.64, 100.0, 200.0, 160.0, 0.0002, 1e-4, 0, 999, 100, "U", 0, "test", Instant.EPOCH, "fp1");
    }

    static GpElementSet decayed() {
        // Mean motion implying perigee below the surface: propagation must fail with a decay error
        return new GpElementSet(99999, "DEBRIS", "2026-999A", Instant.parse("2026-09-09T18:00:00Z"),
                16.8, 0.05, 51.6, 0, 0, 0, 0.5, 0.1, 0, 1, 1, "U", 0, "test", Instant.EPOCH, "fp2");
    }

    @Test
    void alignedTickSnapsToInterval() {
        Instant t = Instant.parse("2026-09-10T00:00:17.345Z");
        assertThat(PropagationTicker.alignedTick(t, 10_000)).isEqualTo(Instant.parse("2026-09-10T00:00:10Z"));
        assertThat(PropagationTicker.alignedTick(t, 60_000)).isEqualTo(Instant.parse("2026-09-10T00:00:00Z"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void propagatesTrackedObjectWithCatalogDenormalised() {
        Catalog catalog = new Catalog();
        catalog.applyCatalog(new CatalogObject(25544, "ISS (ZARYA)", "1998-067A", "PAY", "+", "ISS", "1998-11-20", "TTMTR", null, 399.05, Instant.EPOCH));
        catalog.applyElements(iss());
        KafkaTemplate<String, byte[]> kafka = mock(KafkaTemplate.class);
        PropagationTicker ticker = new PropagationTicker(catalog, kafka, 10_000, new SimpleMeterRegistry());

        StateVectorEvent ev = ticker.propagateOne(catalog.get(25544).orElseThrow(), Instant.parse("2026-09-09T19:30:00Z"));

        assertThat(ev).isNotNull();
        assertThat(ev.owner()).isEqualTo("ISS");
        assertThat(ev.objectType()).isEqualTo("PAY");
        assertThat(ev.regime()).isEqualTo("LEO");
        assertThat(ev.elementAgeHours()).isCloseTo(1.5, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(ev.altKm()).isBetween(380.0, 460.0);
        assertThat(ev.latDeg()).isBetween(-52.0, 52.0);
        assertThat(ev.lonDeg()).isBetween(-180.0, 180.0);
        assertThat(ev.speedKmS()).isBetween(7.5, 7.8);
    }

    @Test
    @SuppressWarnings("unchecked")
    void catalogRowArrivingBeforeElementsIsParkedThenMerged() {
        Catalog catalog = new Catalog();
        catalog.applyCatalog(new CatalogObject(25544, "ISS (ZARYA)", "1998-067A", "PAY", "+", "ISS", null, null, null, null, Instant.EPOCH));
        assertThat(catalog.size()).isZero();
        assertThat(catalog.pendingCatalogRows()).isEqualTo(1);
        catalog.applyElements(iss());
        assertThat(catalog.get(25544).orElseThrow().owner()).isEqualTo("ISS");
        assertThat(catalog.pendingCatalogRows()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void decayedObjectEmitsOneFailureAlertAndIsSkippedAfterwards() {
        Catalog catalog = new Catalog();
        catalog.applyElements(decayed());
        KafkaTemplate<String, byte[]> kafka = mock(KafkaTemplate.class);
        PropagationTicker ticker = new PropagationTicker(catalog, kafka, 10_000, new SimpleMeterRegistry());
        TrackedObject obj = catalog.get(99999).orElseThrow();

        // 10 days after epoch with an absurd drag term the orbit has decayed
        StateVectorEvent ev = ticker.propagateOne(obj, Instant.parse("2026-09-19T18:00:00Z"));

        assertThat(ev).isNull();
        assertThat(obj.hasFailed()).isTrue();
        verify(kafka, times(1)).send(eq(Topics.ALERTS), anyString(), any(byte[].class));
    }

    @Test
    void newElementSetReplacesPropagator() {
        Catalog catalog = new Catalog();
        catalog.applyElements(iss());
        var p1 = catalog.get(25544).orElseThrow().propagator();
        GpElementSet newer = new GpElementSet(25544, "ISS (ZARYA)", "1998-067A", Instant.parse("2026-09-10T06:00:00Z"),
                15.49, 0.0004, 51.64, 100.0, 200.0, 160.0, 0.0002, 1e-4, 0, 999, 100, "U", 0, "test", Instant.EPOCH, "fp3");
        catalog.applyElements(newer);
        var p2 = catalog.get(25544).orElseThrow().propagator();
        assertThat(p2).isNotSameAs(p1);
        assertThat(catalog.elementUpdates()).isEqualTo(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void defersWhileCatalogueIsLoadingButNeverStarvesTicks() {
        Catalog catalog = new Catalog();
        PropagationTicker ticker = new PropagationTicker(catalog, mock(KafkaTemplate.class), 10_000, 1_500, 3, new SimpleMeterRegistry());

        // empty catalogue, nothing applied yet: no reason to defer (the tick itself logs "catalog empty")
        assertThat(ticker.shouldDefer(System.currentTimeMillis())).isFalse();

        catalog.applyElements(iss());
        long applied = catalog.lastElementAppliedAtMs();
        assertThat(ticker.shouldDefer(applied + 200)).as("updates 200 ms ago: still loading").isTrue();
        assertThat(ticker.shouldDefer(applied + 2_000)).as("quiet for 2 s: settled").isFalse();

        // continuous updates: defer at most 3 ticks in a row, then tick anyway
        assertThat(ticker.shouldDefer(applied + 100)).isTrue();
        assertThat(ticker.shouldDefer(applied + 100)).isTrue();
        assertThat(ticker.shouldDefer(applied + 100)).isTrue();
        assertThat(ticker.shouldDefer(applied + 100)).as("4th consecutive: tick despite updates").isFalse();
        assertThat(ticker.shouldDefer(applied + 100)).as("counter reset after ticking").isTrue();
    }
}
