package com.orbitwatch.astro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Validates the Java SGP4 port against Vallado's reference C++ implementation (via the Python
 * {@code sgp4} package, v2.x) across 63 live catalogue objects spanning LEO / MEO / GEO / HEO /
 * graveyard, at nine time offsets from -5 h to +7 days. Tolerance is 1 metre in position and
 * 1 mm/s in velocity — the two implementations agree to floating-point noise.
 *
 * <p>Regenerate {@code sgp4-reference-vectors.json} with the script in {@code tools/gen-reference-vectors.py}.
 */
class Sgp4ReferenceTest {

    static final double POS_TOL_KM = 1e-3;   // 1 m
    static final double VEL_TOL_KMS = 1e-6;  // 1 mm/s

    static JsonNode load() throws Exception {
        try (InputStream in = Sgp4ReferenceTest.class.getResourceAsStream("/sgp4-reference-vectors.json")) {
            return new ObjectMapper().readTree(in);
        }
    }

    static Sgp4 build(JsonNode obj) {
        JsonNode e = obj.get("elements");
        return Sgp4.fromOmm(obj.get("epochDays1950").asDouble(),
                e.get("MEAN_MOTION").asDouble(), e.get("ECCENTRICITY").asDouble(),
                e.get("INCLINATION").asDouble(), e.get("RA_OF_ASC_NODE").asDouble(),
                e.get("ARG_OF_PERICENTER").asDouble(), e.get("MEAN_ANOMALY").asDouble(),
                e.get("BSTAR").asDouble());
    }

    @TestFactory
    List<DynamicTest> matchesReferenceImplementation() throws Exception {
        JsonNode all = load();
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode obj : all) {
            String label = obj.get("name").asText() + " #" + obj.get("noradId").asInt() + " [" + obj.get("regime").asText() + "]";
            tests.add(DynamicTest.dynamicTest(label, () -> {
                Sgp4 sat = build(obj);
                assertThat(sat.isDeepSpace()).as("deep-space branch selection").isEqualTo(obj.get("deepSpace").asBoolean());
                for (JsonNode vec : obj.get("vectors")) {
                    double tsince = vec.get("tsince").asDouble();
                    if (vec.get("error").asInt() != 0) {
                        boolean threw = false;
                        try { sat.propagate(tsince); } catch (Sgp4.PropagationException ex) { threw = true; }
                        assertThat(threw).as("expected propagation error at t=" + tsince).isTrue();
                        continue;
                    }
                    Sgp4.StateVector sv = sat.propagate(tsince);
                    JsonNode r = vec.get("r"), v = vec.get("v");
                    assertThat(sv.x()).as("x @" + tsince).isCloseTo(r.get(0).asDouble(), within(POS_TOL_KM));
                    assertThat(sv.y()).as("y @" + tsince).isCloseTo(r.get(1).asDouble(), within(POS_TOL_KM));
                    assertThat(sv.z()).as("z @" + tsince).isCloseTo(r.get(2).asDouble(), within(POS_TOL_KM));
                    assertThat(sv.vx()).as("vx @" + tsince).isCloseTo(v.get(0).asDouble(), within(VEL_TOL_KMS));
                    assertThat(sv.vy()).as("vy @" + tsince).isCloseTo(v.get(1).asDouble(), within(VEL_TOL_KMS));
                    assertThat(sv.vz()).as("vz @" + tsince).isCloseTo(v.get(2).asDouble(), within(VEL_TOL_KMS));
                }
            }));
        }
        assertThat(tests).hasSizeGreaterThan(50);
        return tests;
    }

    @Test
    void referenceSetCoversAllRegimesAndBothBranches() throws Exception {
        JsonNode all = load();
        List<String> regimes = new ArrayList<>();
        int deep = 0, near = 0;
        for (JsonNode obj : all) {
            regimes.add(obj.get("regime").asText());
            if (obj.get("deepSpace").asBoolean()) deep++; else near++;
        }
        assertThat(regimes).contains("LEO", "MEO", "GEO", "HEO", "OTHER");
        assertThat(deep).isGreaterThan(10);
        assertThat(near).isGreaterThan(10);
    }

    @Test
    void propagationIsReentrantAndOrderIndependent() throws Exception {
        // The deep-space integrator carries state; propagating backwards then forwards must give
        // the same answer as a fresh instance (the reference resets when the sign of t flips).
        JsonNode all = load();
        for (JsonNode obj : all) {
            if (!obj.get("deepSpace").asBoolean()) continue;
            Sgp4 a = build(obj), b = build(obj);
            Sgp4.StateVector fresh;
            try { fresh = a.propagate(4320); } catch (Sgp4.PropagationException e) { continue; }
            b.propagate(-300); b.propagate(10080); b.propagate(90);
            Sgp4.StateVector reused = b.propagate(4320);
            assertThat(reused.x()).isCloseTo(fresh.x(), within(1e-6));
            assertThat(reused.y()).isCloseTo(fresh.y(), within(1e-6));
            assertThat(reused.z()).isCloseTo(fresh.z(), within(1e-6));
        }
    }
}
