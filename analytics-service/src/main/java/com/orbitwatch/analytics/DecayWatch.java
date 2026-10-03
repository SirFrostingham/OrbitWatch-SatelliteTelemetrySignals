package com.orbitwatch.analytics;

import com.orbitwatch.astro.OrbitRegime;
import com.orbitwatch.contracts.AlertEvent;
import com.orbitwatch.contracts.GpElementSet;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** Flags element sets whose perigee or drag term says re-entry is near. One alert per new element set. */
public final class DecayWatch {
    private final AnalyticsProperties.Decay cfg;

    public DecayWatch(AnalyticsProperties.Decay cfg) { this.cfg = cfg; }

    public Optional<AlertEvent> check(GpElementSet e) {
        double perigee = OrbitRegime.perigeeAltKm(e.meanMotion(), e.eccentricity());
        boolean lowPerigee = perigee < cfg.warningPerigeeKm();
        boolean highDrag = e.bstar() > cfg.bstarWarning() && perigee < 300;
        if (!lowPerigee && !highDrag) return Optional.empty();
        AlertEvent.Severity sev = perigee < cfg.criticalPerigeeKm() ? AlertEvent.Severity.CRITICAL : AlertEvent.Severity.WARNING;
        String reason = lowPerigee ? String.format("perigee %.0f km", perigee) : String.format("B* %.2e", e.bstar());
        String id = "decay:" + e.noradId() + ":" + e.fingerprint();
        return Optional.of(new AlertEvent(id, AlertEvent.AlertType.DECAY_WATCH, sev, Instant.now(),
                e.noradId(), e.objectName(), null, null,
                "Decay watch: " + e.objectName() + " (" + reason + ")",
                Map.of("perigeeKm", Math.round(perigee * 10) / 10.0,
                        "apogeeKm", Math.round(OrbitRegime.apogeeAltKm(e.meanMotion(), e.eccentricity()) * 10) / 10.0,
                        "bstar", e.bstar(), "meanMotionDot", e.meanMotionDot(), "epoch", e.epoch().toString()),
                "decay:" + e.noradId()));
    }
}
