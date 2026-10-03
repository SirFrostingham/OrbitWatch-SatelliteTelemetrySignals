package com.orbitwatch.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.orbitwatch.contracts.AlertEvent;
import com.orbitwatch.contracts.StateVectorEvent;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.graphql.GraphQlTest;
import org.springframework.context.annotation.Import;
import org.springframework.graphql.test.tester.GraphQlTester;

/** Schema + resolver wiring test against the real schema file, with no Kafka. */
@GraphQlTest(GraphqlController.class)
@Import(LiveViews.class)
class GraphqlControllerTest {

    @Autowired GraphQlTester tester;
    @Autowired LiveViews views;

    @Test
    void satelliteQueryResolvesNestedAlerts() {
        Instant t = Instant.parse("2026-09-10T00:00:00Z");
        views.onVector(new StateVectorEvent(25544, "ISS (ZARYA)", "PAY", "ISS", "LEO", t, t, 2.0, 1, 2, 3, 4, 5, 6, 47.6, -122.3, 420.0, 7.66));
        views.onAlert(new AlertEvent("c1", AlertEvent.AlertType.CONJUNCTION, AlertEvent.Severity.WARNING, t, 25544, "ISS (ZARYA)", 99, "DEB", "close", Map.of("distanceKm", 3.1), "conj:25544:99"));

        tester.document("""
                { satellite(noradId: 25544) { objectName regime altKm recentAlerts { type severity details { key value } } } }
                """)
                .execute()
                .path("satellite.objectName").entity(String.class).isEqualTo("ISS (ZARYA)")
                .path("satellite.regime").entity(String.class).isEqualTo("LEO")
                .path("satellite.recentAlerts[0].type").entity(String.class).isEqualTo("CONJUNCTION")
                .path("satellite.recentAlerts[0].details[0].value").entity(String.class).isEqualTo("3.1");
    }

    @Test
    void satellitesListFiltersByRegime() {
        Instant t = Instant.now();
        views.onVector(new StateVectorEvent(1, "A", "PAY", "US", "LEO", t, t, 1, 0, 0, 0, 0, 0, 0, 0, 0, 500, 7.5));
        views.onVector(new StateVectorEvent(2, "B", "PAY", "US", "GEO", t, t, 1, 0, 0, 0, 0, 0, 0, 0, 0, 35786, 3.0));
        tester.document("{ satellites(regime: GEO) { noradId } }").execute()
                .path("satellites").entityList(Object.class).hasSize(1)
                .path("satellites[0].noradId").entity(Integer.class).isEqualTo(2);
        assertThat(views.trackedCount()).isGreaterThanOrEqualTo(2);
    }
}
