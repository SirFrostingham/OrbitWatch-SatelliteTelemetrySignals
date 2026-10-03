package com.orbitwatch.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Contract tests: every wire record must round-trip through the shared mapper, and must tolerate
 * unknown fields (forward compatibility). If you add a field, add it here.
 */
class ContractRoundTripTest {

    @Test
    void gpElementSetRoundTrips() {
        GpElementSet e = new GpElementSet(25544, "ISS (ZARYA)", "1998-067A", Instant.parse("2026-09-09T20:27:22.665024Z"),
                15.49, 0.0004, 51.64, 100.1, 200.2, 300.3, 0.0001, 1e-5, 0, 999, 1234, "U", 0,
                "celestrak:active", Instant.parse("2026-09-10T00:00:00Z"), "abc");
        GpElementSet back = Json.read(Json.write(e), GpElementSet.class);
        assertThat(back).isEqualTo(e);
        assertThat(new String(Json.write(e))).contains("\"epoch\":\"2026-09-09T20:27:22.665024Z\"");
    }

    @Test
    void stateVectorRoundTrips() {
        StateVectorEvent s = new StateVectorEvent(25544, "ISS (ZARYA)", "PAY", "ISS", "LEO",
                Instant.parse("2026-09-10T00:00:00Z"), Instant.parse("2026-09-09T20:27:22Z"), 3.5,
                1, 2, 3, 4, 5, 6, 47.6, -122.3, 420.5, 7.66);
        assertThat(Json.read(Json.write(s), StateVectorEvent.class)).isEqualTo(s);
    }

    @Test
    void alertRoundTripsWithDetailsMap() {
        AlertEvent a = new AlertEvent("id1", AlertEvent.AlertType.CONJUNCTION, AlertEvent.Severity.WARNING,
                Instant.parse("2026-09-10T00:00:00Z"), 1, "A", 2, "B", "A and B within 3.2 km",
                Map.of("distanceKm", 3.2, "cell", "12:3:-4"), "conj:1:2");
        AlertEvent back = Json.read(Json.write(a), AlertEvent.class);
        assertThat(back.alertId()).isEqualTo("id1");
        assertThat(back.type()).isEqualTo(AlertEvent.AlertType.CONJUNCTION);
        assertThat(((Number) back.details().get("distanceKm")).doubleValue()).isEqualTo(3.2);
    }

    @Test
    void tickStatsRoundTrips() {
        TickStats t = new TickStats(Instant.parse("2026-09-10T00:00:00Z"), 16000,
                Map.of("LEO", 15000L, "GEO", 500L), Map.of("PAY", 9000L), Map.of("US", 8000L),
                12.5, 30, 12, 180.0, 40000.0, 0, Instant.parse("2026-09-10T00:00:01Z"));
        assertThat(Json.read(Json.write(t), TickStats.class)).isEqualTo(t);
    }

    @Test
    void unknownFieldsAreIgnoredForForwardCompatibility() {
        String json = "{\"noradId\":1,\"objectName\":\"X\",\"someFutureField\":42}";
        CatalogObject c = Json.read(json.getBytes(), CatalogObject.class);
        assertThat(c.noradId()).isEqualTo(1);
        assertThat(c.objectName()).isEqualTo("X");
        assertThat(c.owner()).isNull();
    }
}
