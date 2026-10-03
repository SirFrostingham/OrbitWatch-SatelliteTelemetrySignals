package com.orbitwatch.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.orbitwatch.contracts.AlertEvent;
import com.orbitwatch.contracts.GpElementSet;
import com.orbitwatch.contracts.StateVectorEvent;
import com.orbitwatch.contracts.TickStats;
import com.orbitwatch.contracts.Topics;
import com.orbitwatch.contracts.kafka.JsonSerde;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AnalyticsTopologyTest {

    static final Instant T0 = Instant.parse("2026-09-10T00:00:00Z");
    static final Instant T1 = T0.plusSeconds(10);
    static final Instant T2 = T0.plusSeconds(20);

    TopologyTestDriver driver;
    TestInputTopic<String, StateVectorEvent> vectors;
    TestInputTopic<String, GpElementSet> elements;
    TestOutputTopic<String, TickStats> stats;
    TestOutputTopic<String, AlertEvent> alerts;

    @BeforeEach
    void setUp() {
        AnalyticsProperties cfg = AnalyticsProperties.defaults();
        StreamsBuilder b = new StreamsBuilder();
        AnalyticsTopology.build(b, cfg);
        Properties p = new Properties();
        p.put(StreamsConfig.APPLICATION_ID_CONFIG, "test");
        p.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:1234");
        p.put(StreamsConfig.STATE_DIR_CONFIG, System.getProperty("java.io.tmpdir") + "/ow-test-" + System.nanoTime());
        driver = new TopologyTestDriver(b.build(), p, T0);
        vectors = driver.createInputTopic(Topics.STATE_VECTORS, Serdes.String().serializer(), JsonSerde.of(StateVectorEvent.class).serializer());
        elements = driver.createInputTopic(Topics.GP_ELEMENTS, Serdes.String().serializer(), JsonSerde.of(GpElementSet.class).serializer());
        stats = driver.createOutputTopic(Topics.TICK_STATS, Serdes.String().deserializer(), JsonSerde.of(TickStats.class).deserializer());
        alerts = driver.createOutputTopic(Topics.ALERTS, Serdes.String().deserializer(), JsonSerde.of(AlertEvent.class).deserializer());
    }

    @AfterEach
    void tearDown() { driver.close(); }

    static StateVectorEvent vec(int id, String name, String type, String regime, Instant tick, double x, double y, double z, double alt, double ageHours) {
        return new StateVectorEvent(id, name, type, "US", regime, tick, tick.minusSeconds((long) (ageHours * 3600)), ageHours,
                x, y, z, 0, 7.5, 0, 10, 20, alt, 7.5);
    }

    void send(StateVectorEvent v) { vectors.pipeInput(String.valueOf(v.noradId()), v, v.tick()); }

    @Test
    void tickStatsEmittedWhenWindowCloses() {
        send(vec(1, "A", "PAY", "LEO", T0, 7000, 0, 0, 600, 5));
        send(vec(2, "B", "DEB", "LEO", T0, 0, 7000, 0, 300, 800));    // stale (> 30 d)
        send(vec(3, "C", "PAY", "GEO", T0, 42000, 0, 0, 35786, 10));
        assertThat(stats.isEmpty()).as("window not closed yet").isTrue();

        // wall clock passes tick + interval + grace: partials flush, then merge flushes one grace later
        driver.advanceWallClockTime(Duration.ofSeconds(14));
        assertThat(stats.isEmpty()).as("partials only").isTrue();
        driver.advanceWallClockTime(Duration.ofSeconds(4));

        List<TickStats> out = stats.readValuesToList();
        assertThat(out).hasSizeGreaterThanOrEqualTo(1);
        TickStats s = out.get(0);
        assertThat(s.tick()).isEqualTo(T0);
        assertThat(s.objectsPropagated()).isEqualTo(3);
        assertThat(s.byRegime()).containsEntry("LEO", 2L).containsEntry("GEO", 1L);
        assertThat(s.byObjectType()).containsEntry("PAY", 2L).containsEntry("DEB", 1L);
        assertThat(s.byOwnerTop()).containsEntry("US", 3L);
        assertThat(s.staleElementCount()).isEqualTo(1);
        assertThat(s.minAltitudeKm()).isEqualTo(300);
        assertThat(s.maxAltitudeKm()).isEqualTo(35786);
    }

    @Test
    void closePairAcrossCellBoundaryReportedOnceWithSeverity() {
        // cell size 50 km: 49 and 51 sit in different cells, 2 km apart -> WARNING (< 5 km)
        send(vec(100, "SAT-A", "PAY", "LEO", T0, 6849, 0, 0, 470, 1));
        send(vec(200, "SAT-B", "DEB", "LEO", T0, 6851, 0, 0, 470, 1));
        send(vec(300, "FAR", "PAY", "LEO", T0, -6800, 0, 0, 420, 1));
        // close the window
        send(vec(300, "FAR", "PAY", "LEO", T1, -6800, 0, 0, 420, 1));
        send(vec(300, "FAR", "PAY", "LEO", T2, -6800, 0, 0, 420, 1));

        List<AlertEvent> out = alerts.readValuesToList().stream().filter(a -> a.type() == AlertEvent.AlertType.CONJUNCTION).toList();
        assertThat(out).hasSize(1);
        AlertEvent a = out.get(0);
        assertThat(a.primaryNoradId()).isEqualTo(100);
        assertThat(a.secondaryNoradId()).isEqualTo(200);
        assertThat(a.severity()).isEqualTo(AlertEvent.Severity.WARNING);
        assertThat(((Number) a.details().get("distanceKm")).doubleValue()).isCloseTo(2.0, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(a.dedupeKey()).isEqualTo("conj:100:200");
    }

    @Test
    void repeatedConjunctionIsDedupedWithinTtl() {
        for (Instant t : List.of(T0, T1, T2, T2.plusSeconds(10), T2.plusSeconds(20))) {
            send(vec(100, "SAT-A", "PAY", "LEO", t, 6849, 0, 0, 470, 1));
            send(vec(200, "SAT-B", "DEB", "LEO", t, 6851, 0, 0, 470, 1));
        }
        // three windows closed (T0, T1, T2) but only one alert within the 30-minute TTL
        List<AlertEvent> out = alerts.readValuesToList().stream().filter(a -> a.type() == AlertEvent.AlertType.CONJUNCTION).toList();
        assertThat(out).hasSize(1);
    }

    @Test
    void staleElementsAlertOncePerTtl() {
        for (Instant t : List.of(T0, T1, T2)) send(vec(7, "OLD", "DEB", "LEO", t, 7000, 0, 0, 800, 24 * 45));
        List<AlertEvent> out = alerts.readValuesToList().stream().filter(a -> a.type() == AlertEvent.AlertType.STALE_ELEMENTS).toList();
        assertThat(out).hasSize(1);
        assertThat(out.get(0).primaryNoradId()).isEqualTo(7);
        assertThat(out.get(0).title()).contains("45 days");
    }

    static GpElementSet elems(int id, Instant epoch, double mm, double ecc, double inc, double ndot, double bstar, String fp) {
        return new GpElementSet(id, "OBJ-" + id, "2020-001A", epoch, mm, ecc, inc, 0, 0, 0, bstar, ndot, 0, 999, 1, "U", 0, "t", epoch, fp);
    }

    @Test
    void decayWatchFiresOnLowPerigee() {
        // perigee ~ 240 km: warning; ~ 110 km: critical
        elements.pipeInput("1", elems(1, T0, 16.1, 0.0005, 51.6, 1e-3, 5e-4, "a"));
        elements.pipeInput("2", elems(2, T0, 16.6, 0.0005, 51.6, 1e-3, 5e-4, "b"));
        elements.pipeInput("3", elems(3, T0, 15.5, 0.0005, 51.6, 1e-4, 1e-4, "c")); // ISS-like, fine
        List<AlertEvent> out = alerts.readValuesToList().stream().filter(a -> a.type() == AlertEvent.AlertType.DECAY_WATCH).toList();
        assertThat(out).hasSize(2);
        assertThat(out.get(0).severity()).isEqualTo(AlertEvent.Severity.WARNING);
        assertThat(out.get(1).severity()).isEqualTo(AlertEvent.Severity.CRITICAL);
    }

    @Test
    void maneuverCandidateOnUnexplainedSemiMajorAxisJump() {
        elements.pipeInput("9", elems(9, T0, 15.19, 0.0004, 53.05, 1e-5, 1e-4, "fp1"));
        // 6 hours later, mean motion drops enough for a ≈ +7 km (a Starlink orbit raise)
        elements.pipeInput("9", elems(9, T0.plus(Duration.ofHours(6)), 15.165, 0.0004, 53.05, 1e-5, 1e-4, "fp2"));
        // and a refit only 10 minutes after that must NOT fire (below min separation)
        elements.pipeInput("9", elems(9, T0.plus(Duration.ofHours(6)).plusSeconds(600), 15.16, 0.0004, 53.05, 1e-5, 1e-4, "fp3"));
        List<AlertEvent> out = alerts.readValuesToList().stream().filter(a -> a.type() == AlertEvent.AlertType.MANEUVER_CANDIDATE).toList();
        assertThat(out).hasSize(1);
        AlertEvent a = out.get(0);
        assertThat(a.primaryNoradId()).isEqualTo(9);
        assertThat(((Number) a.details().get("unexplainedDeltaAKm")).doubleValue()).isGreaterThan(5);
    }

    @Test
    void naturalDragDecayIsNotAManeuver() {
        // ndot/2 = 2e-4 rev/day² over 1 day => dn = 4e-4 rev/day; da ≈ -(2/3)(a/n) dn ≈ -0.12 km: explained by drag
        elements.pipeInput("5", elems(5, T0, 15.5, 0.0004, 51.6, 2e-4, 3e-4, "x1"));
        elements.pipeInput("5", elems(5, T0.plus(Duration.ofDays(1)), 15.5004, 0.0004, 51.6, 2e-4, 3e-4, "x2"));
        List<AlertEvent> out = alerts.readValuesToList().stream().filter(a -> a.type() == AlertEvent.AlertType.MANEUVER_CANDIDATE).toList();
        assertThat(out).isEmpty();
    }
}
