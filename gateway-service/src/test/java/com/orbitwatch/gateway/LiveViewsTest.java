package com.orbitwatch.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.orbitwatch.contracts.AlertEvent;
import com.orbitwatch.contracts.StateVectorEvent;
import com.orbitwatch.contracts.TickStats;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class LiveViewsTest {

    static StateVectorEvent v(int id, String name, String regime, Instant tick, double alt) {
        return new StateVectorEvent(id, name, "PAY", "US", regime, tick, tick, 1, 0, 0, 0, 0, 0, 0, 1, 2, alt, 7.5);
    }

    @Test
    void keepsLatestVectorPerObjectAndAdvancesTick() {
        LiveViews views = new LiveViews();
        Instant t0 = Instant.parse("2026-09-10T00:00:00Z"), t1 = t0.plusSeconds(10);
        views.onVector(v(1, "A", "LEO", t1, 500));
        views.onVector(v(1, "A", "LEO", t0, 400)); // late, older tick: ignored
        assertThat(views.vector(1).orElseThrow().altKm()).isEqualTo(500);
        assertThat(views.currentTick()).isEqualTo(t1);
        assertThat(views.trackedCount()).isEqualTo(1);
    }

    @Test
    void queryFiltersAndSorts() {
        LiveViews views = new LiveViews();
        Instant t = Instant.now();
        views.onVector(v(1, "ISS (ZARYA)", "LEO", t, 420));
        views.onVector(v(2, "GOES 16", "GEO", t, 35786));
        views.onVector(v(3, "STARLINK-1", "LEO", t, 550));
        assertThat(views.query("LEO", null, null, null, 10, ApiController.comparator("altKm")))
                .extracting(StateVectorEvent::noradId).containsExactly(1, 3);
        assertThat(views.query(null, null, null, "goes", 10, ApiController.comparator("name")))
                .extracting(StateVectorEvent::noradId).containsExactly(2);
        assertThat(views.query(null, null, null, "3", 10, ApiController.comparator("name")))
                .extracting(StateVectorEvent::noradId).containsExactly(3);
    }

    @Test
    void alertsAreNewestFirstFilteredAndStreamed() {
        LiveViews views = new LiveViews();
        StepVerifier.create(views.alertFlux().take(1))
                .then(() -> {
                    views.onAlert(alert("a1", AlertEvent.AlertType.STALE_ELEMENTS, AlertEvent.Severity.INFO, 5));
                    views.onAlert(alert("a2", AlertEvent.AlertType.CONJUNCTION, AlertEvent.Severity.WARNING, 7));
                })
                .expectNextMatches(a -> a.alertId().equals("a1"))
                .verifyComplete();
        assertThat(views.alerts(null, null, null, 10)).extracting(AlertEvent::alertId).containsExactly("a2", "a1");
        assertThat(views.alerts(AlertEvent.AlertType.CONJUNCTION, null, null, 10)).hasSize(1);
        assertThat(views.alerts(null, AlertEvent.Severity.WARNING, null, 10)).extracting(AlertEvent::alertId).containsExactly("a2");
        assertThat(views.alerts(null, null, 5, 10)).extracting(AlertEvent::alertId).containsExactly("a1");
        assertThat(views.alertCounts()).containsEntry("CONJUNCTION", 1L).containsEntry("STALE_ELEMENTS", 1L);
    }

    @Test
    void statsHistoryIsBounded() {
        LiveViews views = new LiveViews();
        Instant t = Instant.parse("2026-09-10T00:00:00Z");
        for (int i = 0; i < 800; i++) {
            views.onStats(new TickStats(t.plus(Duration.ofSeconds(10L * i)), i, Map.of(), Map.of(), Map.of(), 0, 0, 0, 0, 0, 0, t));
        }
        assertThat(views.statsHistory(1000)).hasSize(720);
        assertThat(views.latestStats().orElseThrow().objectsPropagated()).isEqualTo(799);
    }

    static AlertEvent alert(String id, AlertEvent.AlertType type, AlertEvent.Severity sev, int norad) {
        return new AlertEvent(id, type, sev, Instant.now(), norad, "N" + norad, null, null, "t", Map.of(), id);
    }
}
