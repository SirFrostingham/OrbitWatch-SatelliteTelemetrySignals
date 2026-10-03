package com.orbitwatch.analytics;

import com.orbitwatch.astro.OrbitRegime;
import com.orbitwatch.contracts.AlertEvent;
import com.orbitwatch.contracts.GpElementSet;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Compares consecutive element sets for the same object and flags changes that natural
 * perturbations (drag, as encoded in the element set's own mean-motion derivative) do not explain.
 *
 * <p>This is deliberately a simple, explainable heuristic — the shape of what real manoeuvre
 * detection does (compare observed vs. predicted orbit), not a substitute for it. GP element sets
 * are least-squares fits with their own noise floor, so thresholds are set above that floor and a
 * minimum separation between sets avoids flagging refits of the same observation arc.
 */
public final class ManeuverDetector {
    private final AnalyticsProperties.Maneuver cfg;

    public ManeuverDetector(AnalyticsProperties.Maneuver cfg) { this.cfg = cfg; }

    public Optional<AlertEvent> compare(GpElementSet prev, GpElementSet next) {
        if (prev == null || next == null) return Optional.empty();
        if (!next.epoch().isAfter(prev.epoch())) return Optional.empty();
        Duration dt = Duration.between(prev.epoch(), next.epoch());
        if (dt.compareTo(cfg.minSeparation()) < 0) return Optional.empty();
        double days = dt.toMillis() / 86_400_000.0;

        double aPrev = OrbitRegime.semiMajorAxisKm(prev.meanMotion());
        double aNext = OrbitRegime.semiMajorAxisKm(next.meanMotion());
        double dA = aNext - aPrev;
        // Drag-driven expectation: n ∝ a^-3/2 => da = -(2/3)(a/n) dn, with dn = 2*ndot_over_2*days
        double dnDrag = 2.0 * prev.meanMotionDot() * days;
        double dADrag = -(2.0 / 3.0) * (aPrev / prev.meanMotion()) * dnDrag;
        double dAUnexplained = dA - dADrag;
        double dI = next.inclination() - prev.inclination();
        double dEtimesA = (next.eccentricity() - prev.eccentricity()) * aPrev;

        boolean aFlag = Math.abs(dAUnexplained) > Math.max(cfg.semiMajorAxisKm(), 3 * Math.abs(dADrag));
        boolean iFlag = Math.abs(dI) > cfg.inclinationDeg();
        boolean eFlag = Math.abs(dEtimesA) > cfg.eccentricityTimesA();
        if (!aFlag && !iFlag && !eFlag) return Optional.empty();

        AlertEvent.Severity sev = (Math.abs(dAUnexplained) > 10 * cfg.semiMajorAxisKm() || Math.abs(dI) > 5 * cfg.inclinationDeg())
                ? AlertEvent.Severity.WARNING : AlertEvent.Severity.INFO;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("deltaSemiMajorAxisKm", round(dA, 3));
        details.put("dragExpectedDeltaAKm", round(dADrag, 3));
        details.put("unexplainedDeltaAKm", round(dAUnexplained, 3));
        details.put("deltaInclinationDeg", round(dI, 4));
        details.put("deltaEccTimesAKm", round(dEtimesA, 3));
        details.put("hoursBetweenElementSets", round(days * 24, 2));
        details.put("previousEpoch", prev.epoch().toString());
        details.put("epoch", next.epoch().toString());
        String what = aFlag ? String.format("Δa=%.2f km", dAUnexplained) : iFlag ? String.format("Δi=%.3f°", dI) : String.format("Δe·a=%.2f km", dEtimesA);
        String id = "maneuver:" + next.noradId() + ":" + next.fingerprint();
        return Optional.of(new AlertEvent(id, AlertEvent.AlertType.MANEUVER_CANDIDATE, sev, Instant.now(),
                next.noradId(), next.objectName(), null, null,
                "Manoeuvre candidate: " + next.objectName() + " (" + what + " between element sets)",
                details, "maneuver:" + next.noradId()));
    }

    private static double round(double v, int places) {
        double f = Math.pow(10, places);
        return Math.round(v * f) / f;
    }
}
